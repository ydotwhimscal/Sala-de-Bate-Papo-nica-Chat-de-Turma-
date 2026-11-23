# Chat de Turma — Redes de Computadores I / Tema 6

Sala única de bate-papo em Java, usando **sockets TCP**, com um servidor e vários clientes de terminal. Implementa threads simples, broadcast, notificações de entrada/saída e os bônus `/quem` e `historico_chat.txt`. Sem bibliotecas Java externas, Maven ou interface web.

## Estrutura

```text
src/Servidor.java          servidor TCP e estado compartilhado da sala
src/Cliente.java           cliente de terminal com recepção concorrente
tests/test_integracao.py   testes automatizados em sockets reais
README.md                 documentação e roteiro de apresentação
.gitignore                ignora classes compiladas e conversas privadas
```

## Pré-requisitos e compilação

Em **cada VM Debian**, instale o JDK (não apenas o JRE):

```bash
sudo apt update
sudo apt install default-jdk git
java -version
javac -version
git clone https://github.com/SEU-USUARIO/chat-turma.git
cd chat-turma
mkdir -p out
javac -encoding UTF-8 -d out src/Servidor.java src/Cliente.java
```

Compatível com Java 17 ou superior; validado neste ambiente com OpenJDK 21. O endereço do Git acima é um exemplo: substitua pelo repositório real.

## Rede no VirtualBox (duas máquinas distintas)

1. Desligue as VMs antes de alterar a rede.
2. Em ambas, escolha **Configurações → Rede → Adaptador 1 → Placa em modo Bridge**, selecionando a placa física conectada à sua LAN. Assim ambas recebem IPs da mesma rede. Redes Wi-Fi institucionais podem bloquear comunicação entre dispositivos.
3. Alternativa isolada: use **Rede Interna**, com o mesmo nome `chat-lab` nas duas VMs, e configure endereços estáticos distintos, por exemplo `192.168.50.10/24` e `192.168.50.11/24`, pela configuração de rede do Debian. Rede Interna normalmente não fornece DHCP/internet; instale o JDK antes ou use um segundo adaptador NAT.
4. Execute `ip -br addr` em cada VM e anote os IPs reais. Os IPs acima são exemplos.
5. Na VM cliente, execute `ping -c 3 IP_DA_VM_SERVIDOR`. Ping pode ser bloqueado pelo firewall; a conexão TCP é a verificação final.
6. Se um firewall estiver ativo, autorize TCP 5000 **somente para a rede local**. Se usar UFW: `sudo ufw allow from 192.168.50.0/24 to any port 5000 proto tcp` (ajuste a rede real).

Não use `127.0.0.1` para conectar VMs diferentes: esse endereço aponta para a própria VM. NAT padrão independente em cada VM não oferece comunicação direta sem configuração adicional.

## Executar o servidor

Na VM servidor, dentro do repositório:

```bash
java -cp out Servidor
```

```text
IP de escuta (ex.: 0.0.0.0): 0.0.0.0
Porta: 5000
Servidor ouvindo em 0.0.0.0:5000
```

`0.0.0.0` permite escutar em todas as interfaces IPv4. Os clientes devem informar o **IP real da VM servidor**, não `0.0.0.0`. Para limitar a interface, informe esse IP também na escuta.

Também aceita parâmetros (útil para testes):

```bash
java -cp out Servidor 0.0.0.0 5000
```

Use `Ctrl+C` para encerrar o servidor. O histórico é criado na pasta de trabalho atual, em UTF-8, com data/hora local; novas sessões acrescentam registros sem apagar os anteriores. Cada registro é descarregado imediatamente para o arquivo.

## Executar clientes

Na VM cliente, abra um terminal por aluno:

```bash
java -cp out Cliente
```

Exemplo de interação — o texto digitado aparece aqui junto da saída; a aparência depende do terminal:

```text
IP do servidor: 192.168.50.10
Porta: 5000
Apelido (sem espaços): Maria
OK: conectado como Maria
Digite sua mensagem, /quem para listar ou sair para encerrar.
* Maria entrou na sala *
Olá, turma!
Maria: Olá, turma!
* João entrou na sala *
João: Oi, Maria!
/quem
Online (2): Maria, João
sair
```

Todos recebem a mesma mensagem, **inclusive quem enviou**. O cliente pede IP, porta e apelido; uma thread recebe enquanto a thread principal lê o teclado. Mensagens recebidas podem aparecer no meio de uma linha sendo digitada, pois é um terminal simples, sem editor interativo.

Apelidos: 1–24 letras Unicode, números, `_` ou `-`, sem espaços; `sair` é reservado. Duplicatas são recusadas sem distinguir maiúsculas/minúsculas. `sair` (também aceito em outras capitalizações) encerra; `/quem` é exato e responde somente ao solicitante. Linhas vazias são ignoradas.

## Protocolo e concorrência

TCP transporta bytes, não mensagens. Este protocolo usa **UTF-8 e uma linha por mensagem**, terminada por `\n`:

| Direção | Conteúdo | Efeito |
|---|---|---|
| Cliente → servidor, primeira linha | `Maria` | Registra o apelido |
| Servidor → cliente | `OK: conectado como Maria` ou `ERRO: ...` | Confirma ou recusa identificação |
| Cliente → servidor | `Maria: Olá!` | Broadcast dessa mesma linha |
| Cliente → servidor | `/quem` | Lista online só para esse cliente |
| Cliente → servidor | `sair` | Fecha conexão e notifica demais |
| Servidor → sala | `* Maria entrou na sala *` / `* Maria saiu da sala *` | Evento de presença |

O cliente monta `Nome: Texto` automaticamente. O servidor valida o prefixo contra o apelido registrado para impedir usar o nome de outro aluno. O limite é 4096 caracteres Java na linha inteira. Após identificação, a conexão fica aberta sem timeout; antes dela, há timeout de 30 segundos.

Cada conexão tem uma thread. O mapa de usuários, a gravação do histórico e o envio são protegidos com `synchronized` no monitor da sala: previne alteração simultânea do mapa, apelidos duplicados e mistura de linhas. EOF, erro de socket e `sair` passam pelo mesmo bloco de limpeza; a saída é anunciada uma única vez.

**Limitações conscientes:** projeto didático para LAN confiável, sem TLS, senha, limite de conexões ou proteção completa contra abuso. A leitura de linha acontece antes da validação de tamanho; clientes maliciosos podem enviar linhas enormes. Um cliente lento pode bloquear temporariamente os envios porque broadcast usa escrita bloqueante dentro do monitor. Queda abrupta detectável por EOF/erro remove o participante; perda silenciosa de rede pode demorar a ser percebida sem heartbeat. Não exponha o servidor à internet.

## Testes automatizados

O projeto principal é inteiramente Java. O executor de testes usa somente a biblioteca padrão do Python 3:

```bash
sudo apt install python3
python3 tests/test_integracao.py
```

O teste compila o código, inicia um servidor em loopback numa porta temporária, conecta clientes TCP reais e verifica: entrada, broadcast com acentos (incluindo remetente), `/quem`, apelido duplicado/inválido, tentativa de falsificar identidade, tamanho, mensagens consecutivas, `sair`, desconexão abrupta, histórico e execução do cliente Java. O log de teste fica em diretório temporário e não altera a conversa real.

Os testes em loopback **não comprovam execução em VMs distintas**; isso deve ser demonstrado presencialmente.

## Roteiro da apresentação (24/11)

1. Mostrar `ip -br addr` e o servidor em uma VM, clientes em outra.
2. Conectar Maria e João; observar entrada em ambos os terminais.
3. Enviar mensagens alternadas e explicar TCP, `readLine`/`flush` e as threads.
4. Usar `/quem`; tentar outro cliente com apelido já utilizado.
5. Digitar `sair` em um cliente e mostrar atualização da lista.
6. Mostrar `cat historico_chat.txt` no servidor e explicar persistência append.
7. Mostrar o mapa compartilhado, `synchronized`, limpeza no `finally` e histórico de commits.

## GitHub e entrega

Este pacote contém código pronto, **não um repositório publicado**. No GitHub, crie um repositório vazio chamado `chat-turma`, sem README inicial. Após extrair o pacote:

```bash
git init
git add src .gitignore
git commit -m "Implementa chat TCP com cliente e servidor Java"
git add tests README.md
git commit -m "Adiciona testes de integração e documentação Debian"
git branch -M main
git remote add origin https://github.com/SEU-USUARIO/chat-turma.git
git push -u origin main
```

Configure `git config user.name` e `git config user.email` se o Git solicitar. Autentique pelo mecanismo do GitHub (não use a senha da conta como senha Git). Continue registrando **alterações reais** em commits durante o trabalho; não invente histórico anterior. O `.gitignore` exclui compilados e conversas.

Entregue no Class o link real `https://github.com/SEU-USUARIO/chat-turma`. Verifique que o professor consegue acessar o repositório. Informe os integrantes (até seis) e o tema nos comentários conforme orientação da disciplina.