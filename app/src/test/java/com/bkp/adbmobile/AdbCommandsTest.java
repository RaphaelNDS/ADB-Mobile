package com.bkp.adbmobile;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AdbCommandsTest {

    /** Começo da saída do Android quando um comando como dpm ou cmd falha. */
    private static final String EXCEPTION = "Exception occurred while executing 'set-device-owner':\n";

    // ---------- failure() ----------

    @Test
    public void failure_semSaida_naoEhFalha() {
        assertNull(AdbCommands.failure(null));
        assertNull(AdbCommands.failure(""));
    }

    @Test
    public void failure_saidaNormal_naoEhFalha() {
        assertNull(AdbCommands.failure("eth_device_conn = 2"));
        assertNull(AdbCommands.failure("Success: Device owner set to package br.com.backupautomacao.kiosk"));
    }

    @Test
    public void failure_excecaoNoMeioDeUmDumpsys_naoEhFalha() {
        // Um log ou dumpsys pode citar a frase bem depois do começo; não é o comando que falhou.
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 20; i++) out.append("linha qualquer do dumpsys\n");
        out.append(EXCEPTION).append("java.lang.IllegalStateException: x");
        assertNull(AdbCommands.failure(out.toString()));
    }

    @Test
    public void failure_removerDeviceOwnerDeKioskNormal() {
        String msg = AdbCommands.failure(EXCEPTION
                + "java.lang.SecurityException: Attempt to remove non-test admin ComponentInfo{...}");
        assertTrue(msg, msg.contains("testOnly"));
    }

    @Test
    public void failure_contasNoAparelho() {
        String msg = AdbCommands.failure(EXCEPTION
                + "java.lang.IllegalStateException: Not allowed to set the device owner because there "
                + "are already some accounts on the device");
        assertTrue(msg, msg.contains("contas cadastradas"));
    }

    @Test
    public void failure_variosUsuarios() {
        String msg = AdbCommands.failure(EXCEPTION
                + "java.lang.IllegalStateException: Not allowed to set the device owner because there "
                + "are already several users on the device");
        assertTrue(msg, msg.contains("mais de um usuário"));
    }

    @Test
    public void failure_jaExisteDeviceOwner() {
        String msg = AdbCommands.failure(EXCEPTION
                + "java.lang.IllegalStateException: Trying to set the device owner (x), but device "
                + "owner (y) is already set.");
        assertTrue(msg, msg.contains("Já existe um Device Owner"));
    }

    @Test
    public void failure_aparelhoJaConfigurado() {
        String msg = AdbCommands.failure(EXCEPTION
                + "java.lang.IllegalStateException: Not allowed to set the device owner because the "
                + "device has already been provisioned");
        assertTrue(msg, msg.contains("já foi configurado"));
    }

    @Test
    public void failure_kioskNaoInstalado() {
        String msg = AdbCommands.failure(EXCEPTION
                + "java.lang.IllegalArgumentException: Unknown admin: ComponentInfo{...}");
        assertTrue(msg, msg.contains("não está instalado"));
    }

    @Test
    public void failure_erroDesconhecido_mostraALinhaDaExcecao() {
        assertEquals("O comando falhou: java.lang.IllegalArgumentException: Bad argument",
                AdbCommands.failure(EXCEPTION + "java.lang.IllegalArgumentException: Bad argument\n"
                        + "\tat com.android.server.Foo.bar(Foo.java:1)"));
    }

    @Test
    public void failure_semLinhaDeExcecao_mensagemGenerica() {
        assertEquals("O comando falhou.", AdbCommands.failure(EXCEPTION + "algo deu errado"));
    }

    @Test
    public void failure_abrirAppSemApp() {
        // Saída do "Abrir app" (já filtrada pelo grep) com pacote que não existe.
        String msg = AdbCommands.failure("** No activities found to run, monkey aborted.\n");
        assertNotNull(msg);
        assertTrue(msg, msg.contains("não está instalado"));
    }

    @Test
    public void failure_abrirAppComSucesso_naoEhFalha() {
        // Com o filtro, o sucesso não imprime nada.
        assertNull(AdbCommands.failure(""));
    }

    @Test
    public void presets_abrirAppFiltraOMonkey() {
        String cmd = find("Abrir app").command;
        assertTrue(cmd, cmd.contains("2>&1") && cmd.contains("| grep -E 'aborted"));
    }

    @Test
    public void failure_ignoraMaiusculas() {
        assertNotNull(AdbCommands.failure("EXCEPTION OCCURRED WHILE EXECUTING 'x':\nUNKNOWN ADMIN"));
    }

    // ---------- lista de comandos prontos ----------

    @Test
    public void presets_comecaComTituloETodoItemTemComando() {
        AdbCommands.Preset[] all = AdbCommands.PRESETS;
        assertTrue("a lista deve começar com um título de grupo", all[0].isHeader());
        assertFalse("o último item não pode ser título", all[all.length - 1].isHeader());
        for (int i = 0; i < all.length; i++) {
            AdbCommands.Preset p = all[i];
            assertNotNull("sem nome na posição " + i, p.label);
            assertFalse("o pronto não pode ser marcado como salvo: " + p.label, p.userSaved);
            if (p.isHeader() && all[i + 1].isHeader()) fail("grupo vazio: " + p.label);
        }
    }

    @Test
    public void presets_nomesUnicos() {
        // A última escolha é lembrada pelo nome; dois iguais fariam o app abrir no errado.
        Set<String> seen = new HashSet<>();
        for (AdbCommands.Preset p : AdbCommands.PRESETS) {
            if (p.isHeader()) continue;
            assertTrue("nome repetido: " + p.label, seen.add(p.key()));
        }
    }

    @Test
    public void presets_leituraTemNome() {
        for (AdbCommands.Preset p : AdbCommands.PRESETS) {
            if (p.read != null) assertNotNull("leitura sem nome: " + p.label, p.readName);
        }
    }

    @Test
    public void presets_deviceOwnerPedeConfirmacaoEStatusNao() {
        assertTrue(find("Kiosk: definir como Device Owner").confirm);
        assertTrue(find("Kiosk: remover Device Owner").confirm);
        assertTrue(find("Reiniciar aparelho").confirm);
        AdbCommands.Preset status = find("Kiosk: status");
        assertFalse(status.confirm);
        assertTrue(status.command.contains("br.com.backupautomacao.kiosk"));
    }

    // ---------- positionOf() ----------

    private static final AdbCommands.Preset HEADER = AdbCommands.header("Grupo");
    private static final AdbCommands.Preset BATERIA = new AdbCommands.Preset(
            "Bateria", "dumpsys battery", null, null, false, null);
    private static final AdbCommands.Preset MEMORIA = new AdbCommands.Preset(
            "Memória", "cat /proc/meminfo", null, null, false, null);
    private static final AdbCommands.Preset SALVO_BATERIA = AdbCommands.saved("Bateria", "dumpsys battery | head", false);

    /** [0] Meus comandos, [1] salvo "Bateria", [2] Grupo, [3] Bateria, [4] Memória */
    private static List<AdbCommands.Preset> listWithSaved() {
        return new ArrayList<>(Arrays.asList(
                AdbCommands.header(AdbCommands.MY_COMMANDS), SALVO_BATERIA, HEADER, BATERIA, MEMORIA));
    }

    @Test
    public void positionOf_achaPelaChave() {
        assertEquals(4, AdbCommands.positionOf(listWithSaved(), "Memória", 0));
    }

    @Test
    public void positionOf_salvoENaoSeConfundemComOMesmoNome() {
        List<AdbCommands.Preset> list = listWithSaved();
        assertEquals(1, AdbCommands.positionOf(list, SALVO_BATERIA.key(), 0));
        assertEquals(3, AdbCommands.positionOf(list, BATERIA.key(), 0));
        assertEquals("meu:Bateria", SALVO_BATERIA.key());
    }

    @Test
    public void positionOf_chaveQueNaoExisteMais_primeiroPronto() {
        // Ex.: o comando salvo foi excluído. Volta para o primeiro pronto, não para um salvo.
        assertEquals(3, AdbCommands.positionOf(listWithSaved(), "meu:Excluído", 0));
        assertEquals(3, AdbCommands.positionOf(listWithSaved(), "", 5));
    }

    @Test
    public void positionOf_indiceAntigoContaSoOsProntos() {
        // Até a 2.9 o índice não contava títulos nem salvos: 1 = segundo pronto (Memória).
        assertEquals(3, AdbCommands.positionOf(listWithSaved(), null, 0));
        assertEquals(4, AdbCommands.positionOf(listWithSaved(), null, 1));
    }

    @Test
    public void positionOf_indiceAntigoForaDaLista_primeiroPronto() {
        assertEquals(3, AdbCommands.positionOf(listWithSaved(), null, 99));
    }

    @Test
    public void positionOf_listaReal_ultimaEscolhaPeloNome() {
        List<AdbCommands.Preset> list = Arrays.asList(AdbCommands.PRESETS);
        int at = AdbCommands.positionOf(list, "Kiosk: status", 0);
        assertEquals("Kiosk: status", list.get(at).label);
    }

    // ---------- "Broken pipe" ----------

    /**
     * Quando o grep -m ou o head fecha o pipe antes do fim, o dumpsys escreve "Broken pipe" na
     * saída. Todo dumpsys cortado assim precisa do 2>/dev/null.
     */
    @Test
    public void dumpsysCortadoPeloPipe_escondeOBrokenPipe() {
        List<String> commands = new ArrayList<>();
        for (AdbCommands.Preset p : AdbCommands.PRESETS) {
            if (!p.isHeader()) commands.add(p.label + ": " + p.command);
        }
        commands.add("Adb.FOREGROUND_COMMAND: " + Adb.FOREGROUND_COMMAND);
        for (String c : commands) {
            // Cada trecho "dumpsys ... |" seguido de grep -m ou head.
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("dumpsys[^|;]*\\|\\s*(grep -m|head)").matcher(c);
            while (m.find()) {
                assertTrue("falta 2>/dev/null: " + c, m.group().contains("2>/dev/null"));
            }
        }
    }

    // ---------- restoredCommand() ----------

    private static final String OLD = "dumpsys activity activities | grep -m1 ResumedActivity";
    private static final String NEW = "dumpsys activity activities 2>/dev/null | grep -m1 ResumedActivity";

    @Test
    public void restoredCommand_primeiraVez_comandoAtual() {
        assertEquals(NEW, AdbCommands.restoredCommand(null, null, NEW));
    }

    @Test
    public void restoredCommand_semEdicao_trazOComandoAtualizado() {
        // Executado na 2.18 sem editar; na 2.19 o comando pronto mudou.
        assertEquals(NEW, AdbCommands.restoredCommand(OLD, OLD, NEW));
    }

    @Test
    public void restoredCommand_editado_mantemOTextoDigitado() {
        String edited = OLD + " | cut -d' ' -f3";
        assertEquals(edited, AdbCommands.restoredCommand(edited, OLD, NEW));
    }

    @Test
    public void restoredCommand_outroComandoEscolhidoSemExecutar_trazOEscolhido() {
        // Executou "Bateria", depois escolheu "Memória" na lista e fechou o app sem executar.
        assertEquals("cat /proc/meminfo | head -n 5",
                AdbCommands.restoredCommand("dumpsys battery", "dumpsys battery", "cat /proc/meminfo | head -n 5"));
    }

    @Test
    public void restoredCommand_comandoPersonalizado_mantemOTexto() {
        // "Comando personalizado" tem texto vazio; o que foi digitado é edição.
        assertEquals("getprop ro.product.model",
                AdbCommands.restoredCommand("getprop ro.product.model", "", ""));
    }

    @Test
    public void restoredCommand_salvoAteA219SemBase_mantemOTexto() {
        // Sem base não dá para saber se foi editado: fica como estava (comportamento antigo).
        assertEquals(OLD, AdbCommands.restoredCommand(OLD, null, NEW));
    }

    // ---------- logLine() ----------

    @Test
    public void logLine_comandoProntoSemEdicao_mostraONome() {
        AdbCommands.Preset status = find("Kiosk: status");
        assertEquals("$ Kiosk: status", AdbCommands.logLine(status, status.command, status.command));
    }

    @Test
    public void logLine_comandoSalvo_mostraONome() {
        assertEquals("$ Bateria", AdbCommands.logLine(SALVO_BATERIA, SALVO_BATERIA.command, "dumpsys battery | head"));
    }

    @Test
    public void logLine_espacosNasPontas_aindaContaComoSemEdicao() {
        assertEquals("$ Bateria", AdbCommands.logLine(BATERIA, "dumpsys battery", "  dumpsys battery \n"));
    }

    @Test
    public void logLine_textoEditado_mostraOComando() {
        assertEquals("$ dumpsys battery | grep level",
                AdbCommands.logLine(BATERIA, "dumpsys battery", "dumpsys battery | grep level"));
    }

    @Test
    public void logLine_comandoPersonalizado_mostraOComando() {
        AdbCommands.Preset custom = find("Comando personalizado");
        assertEquals("$ getprop ro.product.model", AdbCommands.logLine(custom, "", "getprop ro.product.model"));
    }

    @Test
    public void logLine_pacoteTrocado_usaOTextoJaPreenchido() {
        // "Versão do app" tem {pacote}; o campo mostra o comando já com o pacote do Log automático.
        AdbCommands.Preset version = find("Versão do app");
        String filled = version.command.replace(AdbCommands.PACKAGE, "br.com.backupautomacao.kiosk");
        assertEquals("$ Versão do app", AdbCommands.logLine(version, filled, filled));
        assertEquals("$ " + version.command, AdbCommands.logLine(version, filled, version.command));
    }

    private static AdbCommands.Preset find(String label) {
        for (AdbCommands.Preset p : AdbCommands.PRESETS) {
            if (label.equals(p.label)) return p;
        }
        throw new AssertionError("comando não encontrado: " + label);
    }

    // ---------- readAfter() ----------

    private static AdbCommands.Preset ethernet() {
        for (AdbCommands.Preset p : AdbCommands.PRESETS) {
            if (Adb.COMMAND.equals(p.command)) return p;
        }
        throw new AssertionError("comando do Ethernet não encontrado");
    }

    @Test
    public void readAfter_semEdicao_leOValor() {
        AdbCommands.Preset eth = ethernet();
        assertEquals(Adb.READ_COMMAND, AdbCommands.readAfter(eth, eth.command, eth.command));
    }

    @Test
    public void readAfter_textoEditado_naoLe() {
        // Visto no tablet: "getprop ro.product.model" com o Ethernet selecionado terminava em
        // "Aplicado neste aparelho. Ethernet (eth_device_conn) = null".
        AdbCommands.Preset eth = ethernet();
        assertNull(AdbCommands.readAfter(eth, eth.command, "getprop ro.product.model"));
    }

    @Test
    public void readAfter_comandoSemLeitura_null() {
        AdbCommands.Preset status = find("Kiosk: status");
        assertNull(AdbCommands.readAfter(status, status.command, status.command));
    }

    // ---------- Vários comandos ----------

    @Test
    public void lote_naOrdemDaLista_ignoraChavesQueSumiram() {
        java.util.List<AdbCommands.Preset> list = java.util.Arrays.asList(HEADER, SALVO_BATERIA, BATERIA, MEMORIA);
        java.util.List<AdbCommands.Preset> steps = AdbCommands.batchSteps(list,
                java.util.Arrays.asList(MEMORIA.key(), SALVO_BATERIA.key(), "comando excluído"));
        assertEquals(java.util.Arrays.asList(SALVO_BATERIA, MEMORIA), steps);
    }

    @Test
    public void lote_linhaDoPasso() {
        assertEquals("[2/3] Bateria", AdbCommands.batchStepLine(1, 3, BATERIA));
    }
}
