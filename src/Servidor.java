import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Sala única: uma thread por cliente e estado da sala protegido pelo mesmo monitor. */
public final class Servidor {
    private final Map<String, Participante> online = new LinkedHashMap<>();
    private final BufferedWriter historico;

    private Servidor(Path log) throws IOException {
        historico = Files.newBufferedWriter(log, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static final class Participante {
        final Socket socket;
        final BufferedWriter saida;
        String nome;
        Participante(Socket socket) throws IOException {
            this.socket = socket;
            saida = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }
        void enviar(String texto) throws IOException {
            saida.write(texto);
            saida.newLine();
            saida.flush();
        }
    }

    // Todas as chamadas ocorrem sob o monitor da sala: linhas não se misturam.
    private void broadcast(String mensagem) {
        System.out.println(mensagem);
        try {
            historico.write("[" + LocalDateTime.now() + "] " + mensagem);
            historico.newLine();
            historico.flush();
        } catch (IOException e) {
            System.err.println("Falha ao gravar histórico: " + e.getMessage());
        }
        for (Participante p : online.values()) {
            try { p.enviar(mensagem); }
            catch (IOException e) {
                // Fechar também desbloqueia a thread leitora, que fará a remoção.
                try { p.socket.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void atender(Socket socket) {
        Participante p = null;
        try (socket;
             BufferedReader entrada = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(30_000); // Evita conexão sem identificação indefinidamente.
            p = new Participante(socket);
            String nome = entrada.readLine();
            if (nome == null) return;
            nome = nome.trim();
            synchronized (this) {
                if (!nome.matches("[\\p{L}\\p{N}_-]{1,24}") || nome.equalsIgnoreCase("sair")) {
                    p.enviar("ERRO: apelido deve ter 1 a 24 letras, números, _ ou -; sair é reservado.");
                    return;
                }
                for (String existente : online.keySet()) {
                    if (existente.equalsIgnoreCase(nome)) {
                        p.enviar("ERRO: apelido já está em uso.");
                        return;
                    }
                }
                p.nome = nome;
                p.enviar("OK: conectado como " + nome);
                online.put(nome, p);
                broadcast("* " + nome + " entrou na sala *");
            }
            socket.setSoTimeout(0);
            String linha;
            while ((linha = entrada.readLine()) != null) {
                if (linha.equalsIgnoreCase("sair")) break;
                synchronized (this) {
                    if (linha.equals("/quem")) {
                        p.enviar("Online (" + online.size() + "): " + String.join(", ", online.keySet()));
                    } else if (!linha.isBlank()) {
                        // O cliente envia Nome: Texto; o servidor valida contra o nickname registrado.
                        String prefixo = p.nome + ": ";
                        if (!linha.startsWith(prefixo) || linha.length() > 4096) {
                            p.enviar("ERRO: use Nome: Texto (máximo de 4096 caracteres).");
                        } else if (!linha.substring(prefixo.length()).isBlank()) {
                            broadcast(linha);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Conexão encerrada: " + e.getMessage());
        } finally {
            synchronized (this) {
                if (p != null && p.nome != null && online.get(p.nome) == p) {
                    online.remove(p.nome);
                    broadcast("* " + p.nome + " saiu da sala *");
                }
            }
        }
    }

    public static void main(String[] args) {
        try {
            BufferedReader terminal = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String ip;
            String portaTexto;
            if (args.length == 2) { ip = args[0]; portaTexto = args[1]; }
            else if (args.length == 0) {
                System.out.print("IP de escuta (ex.: 0.0.0.0): ");
                ip = terminal.readLine();
                System.out.print("Porta: ");
                portaTexto = terminal.readLine();
            } else throw new IllegalArgumentException("Uso: Servidor [IP PORTA]");
            int porta = Integer.parseInt(portaTexto);
            if (porta < 1 || porta > 65535) throw new IllegalArgumentException("Porta entre 1 e 65535.");
            if (ip == null || ip.isBlank()) throw new IllegalArgumentException("Informe o IP de escuta.");
            Servidor sala = new Servidor(Path.of("historico_chat.txt"));
            try (ServerSocket servidor = new ServerSocket()) {
                servidor.bind(new InetSocketAddress(ip, porta));
                System.out.println("Servidor ouvindo em " + ip + ":" + porta);
                while (true) {
                    Socket cliente = servidor.accept();
                    new Thread(() -> sala.atender(cliente), "cliente-" + cliente.getRemoteSocketAddress()).start();
                }
            } finally { sala.historico.close(); }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Não foi possível iniciar o servidor: " + e.getMessage());
            System.exit(1);
        }
    }
}