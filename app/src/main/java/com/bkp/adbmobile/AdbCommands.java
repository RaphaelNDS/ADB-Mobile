package com.bkp.adbmobile;

/**
 * Comandos "adb shell" prontos para escolher na tela. O texto vai para um campo editável,
 * então dá para ajustar antes de executar. {@link #PACKAGE} é trocado pelo pacote informado
 * no Log automático.
 */
public final class AdbCommands {

    /** Marcador trocado pelo pacote do app ao escolher o comando. */
    public static final String PACKAGE = "{pacote}";

    public static final class Preset {
        public final String label;
        public final String command;
        /** Comando que lê o valor resultante ("Ler valor atual"); null se não se aplica. */
        public final String read;
        /** Pede confirmação antes de executar (reinicia, apaga dados etc.). */
        public final boolean confirm;

        Preset(String label, String command, String read, boolean confirm) {
            this.label = label;
            this.command = command;
            this.read = read;
            this.confirm = confirm;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static Preset run(String label, String command) {
        return new Preset(label, command, null, false);
    }

    private static Preset set(String label, String command, String read) {
        return new Preset(label, command, read, false);
    }

    private static Preset danger(String label, String command) {
        return new Preset(label, command, null, true);
    }

    public static final Preset[] PRESETS = {
            set("Ativar conexão Ethernet (eth_device_conn = 2)", Adb.COMMAND, Adb.READ_COMMAND),
            set("Desativar conexão Ethernet (eth_device_conn = 0)",
                    "settings put system eth_device_conn 0", Adb.READ_COMMAND),

            // Informações
            run("Informações do aparelho", Adb.DEVICE_INFO_COMMAND),
            run("Endereços IP", "ip -o -4 addr show | awk '{print $2, $4}'"),
            run("Bateria", "dumpsys battery"),
            run("Espaço em disco", "df -h /data /sdcard"),
            run("Memória", "cat /proc/meminfo | head -n 5"),
            run("Tela: resolução e densidade", "wm size; wm density"),
            run("Data, hora e tempo ligado", "date; uptime"),
            run("App em primeiro plano", Adb.FOREGROUND_COMMAND),

            // Apps
            run("Listar apps instalados (de terceiros)", "pm list packages -3"),
            run("Versão do app", "dumpsys package " + PACKAGE + " | grep -m2 -E 'versionName|versionCode'"),
            run("Abrir app", "monkey -p " + PACKAGE + " -c android.intent.category.LAUNCHER 1"),
            run("Forçar parada do app", "am force-stop " + PACKAGE),
            danger("Limpar dados do app", "pm clear " + PACKAGE),
            danger("Desinstalar app", "pm uninstall " + PACKAGE),

            // Configurações
            set("Tela: desligar após 30 min", "settings put system screen_off_timeout 1800000",
                    "settings get system screen_off_timeout"),
            set("Tela: manter ligada enquanto carrega", "settings put global stay_on_while_plugged_in 7",
                    "settings get global stay_on_while_plugged_in"),
            set("Brilho máximo", "settings put system screen_brightness_mode 0; "
                    + "settings put system screen_brightness 255", "settings get system screen_brightness"),
            set("Desativar animações", "settings put global window_animation_scale 0; "
                    + "settings put global transition_animation_scale 0; "
                    + "settings put global animator_duration_scale 0",
                    "settings get global animator_duration_scale"),
            set("Ativar animações", "settings put global window_animation_scale 1; "
                    + "settings put global transition_animation_scale 1; "
                    + "settings put global animator_duration_scale 1",
                    "settings get global animator_duration_scale"),
            run("Ligar Wi-Fi", "svc wifi enable"),
            run("Abrir Configurações", "am start -a android.settings.SETTINGS"),

            // Teclas e energia
            run("Tecla Home", "input keyevent KEYCODE_HOME"),
            run("Tecla Voltar", "input keyevent KEYCODE_BACK"),
            run("Ligar/desligar tela", "input keyevent KEYCODE_POWER"),
            danger("Reiniciar aparelho", "reboot"),

            new Preset("Comando personalizado", "", null, false),
    };

    private AdbCommands() {
    }
}
