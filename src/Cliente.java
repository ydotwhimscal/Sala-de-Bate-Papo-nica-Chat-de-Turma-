import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Envio no terminal principal e recebimento concorrente em uma thread. */
public final class Cliente {
    public static void main(String[] args) {
        try {
            BufferedReader terminal = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            System.out.print("IP do servidor: ");
            String ip = terminal.readLine();
            System.out.print("Porta: ");
            int porta = Integer.parseInt(terminal.readLine());
            if (ip == null || ip.isBlank() || porta < 1 || porta > 65535)
                throw new IllegalArgumentException("IP obrigatório e porta entre 1 e 65535.");
            System.out.print("Apelido (sem espaços): ");
            String nome = terminal.readLine();
            if (nome == null) return;
            nome = nome.trim();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, porta), 5000);
                BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter saida = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                enviar(saida, nome);
                socket.setSoTimeout(5000);
                String resposta = entrada.readLine();
                if (resposta == null || !resposta.startsWith("OK:")) {
                    System.err.println(resposta == null ? "Servidor fechou a conexão." : resposta);
                    return;
                }
                socket.setSoTimeout(0);
                System.out.println(resposta);
                System.out.println("Digite sua mensagem, /quem para listar ou sair para encerrar.");
                Thread receptor = new Thread(() -> {
                    try {
                        String mensagem;
                        while ((mensagem = entrada.readLine()) != null) System.out.println(mensagem);
                    } catch (IOException e) {
                        if (!socket.isClosed()) System.err.println("Recepção interrompida: " + e.getMessage());
                    } finally {
                        if (!socket.isClosed()) {
                            System.out.println("Servidor desconectou. Encerrando cliente.");
                            // readLine do terminal não é interrompível: encerra também o envio bloqueado.
                            System.exit(0);
                        }
                    }
                }, "recepcao");
                receptor.setDaemon(true);
                receptor.start();
                String texto;
                while ((texto = terminal.readLine()) != null) {
                    if (texto.equalsIgnoreCase("sair")) {
                        enviar(saida, "sair");
                        break;
                    }
                    if (!texto.isBlank()) {
                        String mensagem = texto.equals("/quem") ? texto : nome + ": " + texto;
                        if (mensagem.length() > 4096) System.err.println("Mensagem muito longa (limite: 4096 caracteres incluindo nome).");
                        else enviar(saida, mensagem);
                    }
                }
                socket.close();
                receptor.join(1000);
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Erro no cliente: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void enviar(BufferedWriter saida, String linha) throws IOException {
        saida.write(linha);
        saida.newLine();
        saida.flush();
    }
}