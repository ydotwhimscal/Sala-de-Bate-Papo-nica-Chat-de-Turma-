"""Testes reais de sockets TCP; somente biblioteca padrão do Python 3."""
import pathlib
import socket
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class ChatTest(unittest.TestCase):
    def test_fluxo_completo(self):
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(ROOT / 'out'),
                        *map(str, (ROOT / 'src').glob('*.java'))], check=True)
        with socket.socket() as reserva:
            reserva.bind(('127.0.0.1', 0))
            porta = reserva.getsockname()[1]
        with tempfile.TemporaryDirectory() as pasta:
            servidor = subprocess.Popen(['java', '-cp', str(ROOT / 'out'), 'Servidor',
                                         '127.0.0.1', str(porta)], cwd=pasta,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            conexoes = []
            arquivos = []
            try:
                self.assertIn('Servidor ouvindo', servidor.stdout.readline())

                def conectar(nome):
                    s = socket.create_connection(('127.0.0.1', porta), timeout=3)
                    conexoes.append(s)
                    f = s.makefile('rw', encoding='utf-8', newline='\n')
                    arquivos.append(f)
                    f.write(nome + '\n')
                    f.flush()
                    return s, f

                def ler(f, esperado):
                    self.assertEqual(f.readline().strip(), esperado)

                def enviar(f, texto):
                    f.write(texto + '\n')
                    f.flush()

                _, ana = conectar('Ana')
                ler(ana, 'OK: conectado como Ana')
                ler(ana, '* Ana entrou na sala *')
                socket_b, bia = conectar('Bia')
                ler(bia, 'OK: conectado como Bia')
                ler(bia, '* Bia entrou na sala *')
                ler(ana, '* Bia entrou na sala *')
                enviar(ana, 'Ana: Olá, turma!')
                for f in (ana, bia):
                    ler(f, 'Ana: Olá, turma!')
                enviar(ana, '/quem')
                ler(ana, 'Online (2): Ana, Bia')
                _, duplicado = conectar('aNA')
                self.assertIn('já está em uso', duplicado.readline())
                _, invalido = conectar('nome com espaços')
                self.assertTrue(invalido.readline().startswith('ERRO:'))
                enviar(ana, 'Bia: identidade falsa')
                self.assertTrue(ana.readline().startswith('ERRO:'))
                enviar(ana, 'Ana: ' + 'x' * 4096)
                self.assertTrue(ana.readline().startswith('ERRO:'))
                # Duas mensagens seguidas: fronteiras são linhas, não pacotes TCP.
                enviar(ana, 'Ana: primeira\nAna: segunda')
                for f in (ana, bia):
                    ler(f, 'Ana: primeira')
                    ler(f, 'Ana: segunda')
                enviar(bia, 'sair')
                ler(ana, '* Bia saiu da sala *')
                bia.close()
                socket_b.close()
                socket_c, caio = conectar('Caio')
                ler(caio, 'OK: conectado como Caio')
                ler(caio, '* Caio entrou na sala *')
                ler(ana, '* Caio entrou na sala *')
                caio.close()
                socket_c.close()  # Saída abrupta, sem comando sair.
                ler(ana, '* Caio saiu da sala *')
                enviar(ana, '/quem')
                ler(ana, 'Online (1): Ana')
                log = pathlib.Path(pasta, 'historico_chat.txt').read_text(encoding='utf-8')
                self.assertIn('Ana: Olá, turma!', log)
                self.assertIn('* Bia saiu da sala *', log)
                self.assertNotIn('identidade falsa', log)
                # Exercita também o cliente de terminal como processo real.
                cliente = subprocess.run(['java', '-cp', str(ROOT / 'out'), 'Cliente'],
                                         input=f'127.0.0.1\n{porta}\nDavi\nOlá!\nsair\n',
                                         capture_output=True, text=True, timeout=5)
                self.assertEqual(cliente.returncode, 0, cliente.stderr)
                self.assertIn('OK: conectado como Davi', cliente.stdout)
                ler(ana, '* Davi entrou na sala *')
                ler(ana, 'Davi: Olá!')
                ler(ana, '* Davi saiu da sala *')
            finally:
                for f in arquivos:
                    f.close()
                for s in conexoes:
                    s.close()
                servidor.terminate()
                servidor.communicate(timeout=5)


if __name__ == '__main__':
    unittest.main(verbosity=2)