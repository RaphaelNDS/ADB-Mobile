package com.bkp.adbmobile;

import java.util.Locale;

/**
 * Comandos "adb shell" prontos para escolher na tela. O texto vai para um campo editável,
 * então dá para ajustar antes de executar. {@link #PACKAGE} é trocado pelo pacote informado
 * no Log automático.
 */
public final class AdbCommands {

    /** Marcador trocado pelo pacote do app ao escolher o comando. */
    public static final String PACKAGE = "{pacote}";

    /** Componente administrador do app Kiosk (Device Owner). */
    private static final String KIOSK_ADMIN =
            "br.com.backupautomacao.kiosk/br.com.backupautomacao.kiosk.admin.AdminReceiver";

    /**
     * Mostra o componente do Device Owner atual, ou "nenhum". Usa o dumpsys, que existe em todas
     * as versões ("dpm list-owners" só existe a partir do Android 10). O trecho lido é:
     * <pre>
     *   Device Owner:
     *     admin=ComponentInfo{pacote/classe}
     * </pre>
     */
    private static final String DEVICE_OWNER_READ = "o=$(dumpsys device_policy | grep -A2 'Device Owner:'"
            + " | grep -o 'ComponentInfo{[^}]*}' | head -n 1 | sed 's/ComponentInfo{\\(.*\\)}/\\1/');"
            + " echo ${o:-nenhum}";

    /** Pacote do app Kiosk (o mesmo de {@link #KIOSK_ADMIN}). */
    private static final String KIOSK_PACKAGE = "br.com.backupautomacao.kiosk";

    /**
     * Resumo do Kiosk em quatro linhas: versão, se é o Device Owner, se está em primeiro plano e
     * o estado do modo quiosque (Lock Task). Os dumpsys vão com 2>/dev/null porque o grep -m1
     * fecha o pipe antes do fim e o dumpsys reclama de "Broken pipe".
     */
    private static final String KIOSK_STATUS = "p=" + KIOSK_PACKAGE + ";"
            + " v=$(dumpsys package $p 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//');"
            + " c=$(dumpsys package $p 2>/dev/null | grep -m1 versionCode | sed 's/.*versionCode=\\([0-9]*\\).*/\\1/');"
            + " if [ -n \"$v\" ]; then echo \"Versão do Kiosk: $v ($c)\"; else echo 'Versão do Kiosk: não instalado'; fi;"
            + " o=$(dumpsys device_policy 2>/dev/null | grep -A2 'Device Owner:' | grep -o 'ComponentInfo{[^}]*}'"
            + " | head -n 1 | sed 's/ComponentInfo{\\(.*\\)}/\\1/');"
            + " case \"$o\" in $p/*) echo 'Device Owner: sim';; '') echo 'Device Owner: não (nenhum definido)';;"
            + " *) echo \"Device Owner: não (atual: $o)\";; esac;"
            + " f=$(dumpsys activity activities 2>/dev/null | grep -m1 ResumedActivity"
            + " | sed 's/.* u[0-9]* \\([^/ ]*\\)\\/.*/\\1/');"
            + " if [ \"$f\" = \"$p\" ]; then echo 'Em primeiro plano: sim';"
            + " else echo \"Em primeiro plano: não (atual: ${f:-desconhecido})\"; fi;"
            + " l=$(dumpsys activity 2>/dev/null | grep -m1 mLockTaskModeState | sed 's/.*=//');"
            + " case \"$l\" in LOCKED) echo 'Modo quiosque (Lock Task): ativo';;"
            + " PINNED) echo 'Modo quiosque (Lock Task): tela fixada';;"
            + " NONE) echo 'Modo quiosque (Lock Task): inativo';;"
            + " *) echo 'Modo quiosque (Lock Task): desconhecido';; esac";

    private static final String DEVICE_OWNER = "Device Owner";
    private static final String ETHERNET = "Ethernet (eth_device_conn)";
    private static final String ANIMATIONS = "Escala das animações";

    public static final class Preset {
        public final String label;
        public final String command;
        /** Comando que lê o valor resultante ("Ler valor atual"); null se não se aplica. */
        public final String read;
        /** Nome do valor lido, mostrado como "nome = valor". */
        public final String readName;
        /** Pede confirmação antes de executar (reinicia, apaga dados etc.). */
        public final boolean confirm;
        /** Aviso extra mostrado na confirmação; null se não há. */
        public final String warning;
        /** Comando salvo pela pessoa (grupo "Meus comandos"), que pode ser alterado ou excluído. */
        public final boolean userSaved;

        Preset(String label, String command, String read, String readName, boolean confirm, String warning) {
            this(label, command, read, readName, confirm, warning, false);
        }

        private Preset(String label, String command, String read, String readName, boolean confirm,
                       String warning, boolean userSaved) {
            this.label = label;
            this.command = command;
            this.read = read;
            this.readName = readName;
            this.confirm = confirm;
            this.warning = warning;
            this.userSaved = userSaved;
        }

        /** Título de grupo na lista (sem comando, não selecionável). */
        public boolean isHeader() {
            return command == null;
        }

        /**
         * Identifica o item para lembrar a última escolha. Os salvos levam um prefixo para um
         * comando salvo com o mesmo nome de um pronto (ex.: "Bateria") não ser confundido com ele.
         */
        public String key() {
            return userSaved ? SAVED_KEY_PREFIX + label : label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static Preset header(String title) {
        return new Preset(title, null, null, null, false, null);
    }

    /** Título do grupo dos comandos salvos pela pessoa. */
    public static final String MY_COMMANDS = "Meus comandos";

    private static final String SAVED_KEY_PREFIX = "meu:";

    /** Comando salvo pela pessoa, com nome próprio. */
    public static Preset saved(String name, String command, boolean confirm) {
        return new Preset(name, command, null, null, confirm, null, true);
    }

    private static Preset run(String label, String command) {
        return new Preset(label, command, null, null, false, null);
    }

    private static Preset set(String label, String command, String read, String readName) {
        return new Preset(label, command, read, readName, false, null);
    }

    private static Preset danger(String label, String command) {
        return new Preset(label, command, null, null, true, null);
    }

    /**
     * Traduz falhas conhecidas na saída de um comando (o shell não devolve o código de saída,
     * então o erro só aparece no texto). Só considera falha quando a saída começa com a exceção
     * impressa pelos comandos do Android (dpm, cmd etc.), para não confundir com textos de
     * dumpsys ou logs. Devolve null se a saída não indica falha.
     */
    public static String failure(String output) {
        if (output == null) return null;
        String o = output.toLowerCase(Locale.ROOT);
        int at = o.indexOf("exception occurred while executing");
        if (at < 0 || at > 200) return null;
        if (o.contains("non-test admin")) {
            return "O Android só permite remover o Device Owner pelo ADB quando o Kiosk é uma versão "
                    + "de teste (testOnly). Remova pelo próprio app Kiosk ou restaure o aparelho de fábrica.";
        }
        if (o.contains("already some accounts") || o.contains("accounts on the device")) {
            return "Há contas cadastradas no aparelho (Google, Samsung etc.). Remova todas em "
                    + "Configurações > Contas e tente de novo.";
        }
        if (o.contains("several users") || o.contains("multiple users")) {
            return "Há mais de um usuário no aparelho. Remova os outros usuários e tente de novo.";
        }
        // "Trying to set the device owner (...), but device owner (...) is already set."
        if (o.contains("is already set") || o.contains("already has a device owner")
                || o.contains("already has an owner")) {
            return "Já existe um Device Owner neste aparelho. Remova o atual antes de definir outro.";
        }
        if (o.contains("already provisioned") || o.contains("already been provisioned")) {
            return "O aparelho já foi configurado e o Android não aceita mais um Device Owner. "
                    + "Restaure de fábrica e defina antes de adicionar contas.";
        }
        if (o.contains("unknown admin") || o.contains("bad admin")) {
            return "O app Kiosk não está instalado no aparelho (ou não tem o administrador "
                    + "br.com.backupautomacao.kiosk.admin.AdminReceiver).";
        }
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("java.") || line.startsWith("android.")) {
                return "O comando falhou: " + line;
            }
        }
        return "O comando falhou.";
    }

    /** Lista mostrada na tela; os títulos de grupo ({@link Preset#isHeader()}) não são selecionáveis. */
    public static final Preset[] PRESETS = {
            header("Ethernet"),
            set("Ativar conexão Ethernet (eth_device_conn = 2)", Adb.COMMAND, Adb.READ_COMMAND, ETHERNET),
            set("Desativar conexão Ethernet (eth_device_conn = 0)",
                    "settings put system eth_device_conn 0", Adb.READ_COMMAND, ETHERNET),

            header("Informações"),
            run("Informações do aparelho", Adb.DEVICE_INFO_COMMAND),
            run("Endereços IP", "ip -o -4 addr show | awk '{print $2, $4}'"),
            run("Bateria", "dumpsys battery"),
            run("Espaço em disco", "df -h /data /sdcard"),
            run("Memória", "cat /proc/meminfo | head -n 5"),
            run("Tela: resolução e densidade", "wm size; wm density"),
            run("Data, hora e tempo ligado", "date; uptime"),
            run("App em primeiro plano", Adb.FOREGROUND_COMMAND),

            header("Apps"),
            run("Listar apps instalados (de terceiros)", "pm list packages -3"),
            run("Versão do app", "dumpsys package " + PACKAGE + " | grep -m2 -E 'versionName|versionCode'"),
            run("Abrir app", "monkey -p " + PACKAGE + " -c android.intent.category.LAUNCHER 1"),
            run("Forçar parada do app", "am force-stop " + PACKAGE),
            danger("Limpar dados do app", "pm clear " + PACKAGE),
            danger("Desinstalar app", "pm uninstall " + PACKAGE),

            header("Kiosk (Device Owner)"),
            run("Kiosk: status", KIOSK_STATUS),
            new Preset("Kiosk: definir como Device Owner",
                    "dpm set-device-owner " + KIOSK_ADMIN, DEVICE_OWNER_READ, DEVICE_OWNER, true,
                    "O aparelho não pode ter contas (Google, Samsung etc.), outros usuários nem outro "
                            + "Device Owner.\n\nDepois de definido, só o próprio Kiosk ou uma restauração "
                            + "de fábrica removem."),
            new Preset("Kiosk: remover Device Owner",
                    "dpm remove-active-admin " + KIOSK_ADMIN, DEVICE_OWNER_READ, DEVICE_OWNER, true,
                    "Só funciona se o Kiosk instalado for uma versão de teste (testOnly). Na versão "
                            + "normal o Android recusa; nesse caso remova pelo próprio Kiosk."),

            header("Configurações"),
            set("Tela: desligar após 30 min", "settings put system screen_off_timeout 1800000",
                    "settings get system screen_off_timeout", "Desligar tela após (ms)"),
            set("Tela: manter ligada enquanto carrega", "settings put global stay_on_while_plugged_in 7",
                    "settings get global stay_on_while_plugged_in", "Manter ligada carregando"),
            set("Brilho máximo", "settings put system screen_brightness_mode 0; "
                    + "settings put system screen_brightness 255", "settings get system screen_brightness",
                    "Brilho (0-255)"),
            set("Desativar animações", "settings put global window_animation_scale 0; "
                    + "settings put global transition_animation_scale 0; "
                    + "settings put global animator_duration_scale 0",
                    "settings get global animator_duration_scale", ANIMATIONS),
            set("Ativar animações", "settings put global window_animation_scale 1; "
                    + "settings put global transition_animation_scale 1; "
                    + "settings put global animator_duration_scale 1",
                    "settings get global animator_duration_scale", ANIMATIONS),
            run("Ligar Wi-Fi", "svc wifi enable"),
            run("Abrir Configurações", "am start -a android.settings.SETTINGS"),

            header("Teclas e energia"),
            run("Tecla Home", "input keyevent KEYCODE_HOME"),
            run("Tecla Voltar", "input keyevent KEYCODE_BACK"),
            run("Ligar/desligar tela", "input keyevent KEYCODE_POWER"),
            danger("Reiniciar aparelho", "reboot"),

            header("Outro"),
            new Preset("Comando personalizado", "", null, null, false, null),
    };

    private AdbCommands() {
    }
}
