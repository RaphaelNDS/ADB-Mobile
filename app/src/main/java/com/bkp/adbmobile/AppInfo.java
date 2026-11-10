package com.bkp.adbmobile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lista os apps de um aparelho e lê as informações de um pacote, tudo pelo shell do ADB. */
public final class AppInfo {

    private static final String SEP = "__ADB_MOBILE_SEP__";

    /** Um app da lista: pacote, se é do sistema e se está em primeiro plano. */
    public static final class App {
        public final String pkg;
        public final boolean system;
        public String label;
        public boolean foreground;

        App(String pkg, boolean system) {
            this.pkg = pkg;
            this.system = system;
        }

        @Override
        public String toString() {
            String name = label == null || label.equals(pkg) ? pkg : label + "\n" + pkg;
            return (foreground ? "▶ " : "") + name + (system ? "  (sistema)" : "");
        }
    }

    /** Apps instalados no aparelho; o que está em primeiro plano vem primeiro. */
    public static List<App> list(Adb.Shell shell) throws Exception {
        String out = shell.run("pm list packages -3; echo " + SEP + "; pm list packages -s; echo " + SEP + "; "
                + Adb.FOREGROUND_COMMAND);
        String[] parts = out.split(SEP, -1);
        if (parts.length < 3) throw new IOException("Resposta inesperada do aparelho: " + out);
        List<App> apps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addPackages(apps, seen, parts[0], false);
        addPackages(apps, seen, parts[1], true);
        if (apps.isEmpty()) throw new IOException("O aparelho não devolveu a lista de apps: " + out.trim());
        Matcher fg = Pattern.compile("u\\d+ ([^/\\s]+)/").matcher(parts[2]);
        String foreground = fg.find() ? fg.group(1) : "";
        for (App a : apps) a.foreground = a.pkg.equals(foreground);
        sort(apps);
        return apps;
    }

    private static void addPackages(List<App> apps, Set<String> seen, String text, boolean system) {
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.startsWith("package:")) continue;
            String pkg = line.substring(8).trim();
            if (!pkg.isEmpty() && seen.add(pkg)) apps.add(new App(pkg, system));
        }
    }

    /** Primeiro plano, depois os instalados pelo usuário, depois os do sistema; cada grupo por nome. */
    public static void sort(List<App> apps) {
        Collections.sort(apps, (a, b) -> {
            if (a.foreground != b.foreground) return a.foreground ? -1 : 1;
            if (a.system != b.system) return a.system ? 1 : -1;
            String x = a.label != null ? a.label : a.pkg;
            String y = b.label != null ? b.label : b.pkg;
            return x.compareToIgnoreCase(y);
        });
    }

    /** Informações de um pacote, já interpretadas, e a saída completa do dumpsys. */
    public static final class Details {
        public final String pkg;
        public final String text;
        public final String dumpsys;
        public boolean system;
        public boolean enabled;
        public boolean running;
        /** Activity da tela inicial (pacote/classe) ou null se o app não tem ícone. */
        public String launcher;

        Details(String pkg, String text, String dumpsys) {
            this.pkg = pkg;
            this.text = text;
            this.dumpsys = dumpsys;
        }
    }

    /** Ações sobre um app; {@link #confirm} pede confirmação antes (perde dados ou some do aparelho). */
    public enum Action {
        OPEN("Abrir", false),
        STOP("Forçar parada", false),
        ENABLE("Ativar", false),
        DISABLE("Desativar", true),
        CLEAR("Limpar dados", true),
        UNINSTALL("Desinstalar", true);

        public final String label;
        public final boolean confirm;

        Action(String label, boolean confirm) {
            this.label = label;
            this.confirm = confirm;
        }
    }

    /** Ações que fazem sentido no estado atual do app. */
    public static List<Action> actions(Details d) {
        List<Action> list = new ArrayList<>();
        if (d.enabled && d.launcher != null) list.add(Action.OPEN);
        if (d.running) list.add(Action.STOP);
        list.add(d.enabled ? Action.DISABLE : Action.ENABLE);
        list.add(Action.CLEAR);
        // Desinstalar app do sistema exige root; o "Desativar" resolve.
        if (!d.system) list.add(Action.UNINSTALL);
        return list;
    }

    /** Executa a ação; devolve a mensagem de sucesso ou lança o erro do aparelho. */
    public static String run(Adb.Shell shell, Details d, Action action) throws Exception {
        String pkg = d.pkg;
        if (!validPackage(pkg)) throw new IOException("Pacote inválido: " + pkg);
        String out;
        switch (action) {
            case OPEN:
                out = shell.run("am start -n " + d.launcher + " 2>&1");
                if (out.contains("Error")) throw new IOException(out.trim());
                return "Aberto: " + pkg;
            case STOP:
                shell.run("am force-stop " + pkg);
                return "Parado à força: " + pkg;
            case ENABLE:
                out = shell.run("pm enable " + pkg + " 2>&1");
                if (!out.contains("enabled")) throw new IOException(out.trim());
                return "Ativado: " + pkg;
            case DISABLE:
                out = shell.run("pm disable-user --user 0 " + pkg + " 2>&1");
                if (!out.contains("disabled")) throw new IOException(out.trim());
                return "Desativado: " + pkg;
            case CLEAR:
                out = shell.run("pm clear " + pkg + " 2>&1");
                if (!out.contains("Success")) throw new IOException(out.trim());
                return "Dados apagados: " + pkg;
            case UNINSTALL:
                out = shell.run("pm uninstall " + pkg + " 2>&1");
                if (!out.contains("Success")) throw new IOException(out.trim());
                return "Desinstalado: " + pkg;
            default:
                throw new IllegalArgumentException(action.name());
        }
    }

    public static boolean validPackage(String pkg) {
        return pkg.matches("[A-Za-z0-9._]+");
    }

    /** Lê versão, datas, caminhos, estado e permissões do pacote; lança erro se não estiver instalado. */
    public static Details details(Adb.Shell shell, String pkg) throws Exception {
        if (!validPackage(pkg)) throw new IOException("Pacote inválido: " + pkg);
        // Com pacote inexistente o "dumpsys package" despeja todos os apps (demora); por isso o "if".
        String out = shell.run("p=$(pm path " + pkg + " 2>/dev/null); echo \"$p\"; if [ -n \"$p\" ]; then echo "
                + SEP + "; pidof " + pkg + " 2>/dev/null; echo " + SEP
                + "; cmd package resolve-activity --brief -c android.intent.category.LAUNCHER " + pkg
                + " 2>/dev/null | tail -n 1; echo " + SEP
                + "; du -sk $(echo \"$p\" | sed 's/^package://; s#/[^/]*$##' | sort -u)"
                + " 2>/dev/null | awk '{s+=$1} END {print s}'; echo " + SEP + "; dumpsys package " + pkg + "; fi");
        String[] parts = out.split(SEP, -1);
        if (parts.length == 1 && parts[0].trim().isEmpty())
            throw new IOException(pkg + " não está instalado neste aparelho.");
        if (parts.length < 5) throw new IOException("Resposta inesperada do aparelho: " + out);
        String paths = parts[0].trim();
        String pid = parts[1].trim();
        String launcher = parts[2].trim();
        String sizeKb = parts[3].trim();
        String dump = parts[4].trim();
        String section = packageSection(dump, pkg);
        if (paths.isEmpty() || section == null) throw new IOException(pkg + " não está instalado neste aparelho.");

        StringBuilder b = new StringBuilder();
        line(b, "Pacote", pkg);
        line(b, "Versão", join(find(section, "versionName=(\\S+)"), find(section, "versionCode=(\\d+)"), " (código ", ")"));
        line(b, "Android mínimo", sdk(find(section, "minSdk=(\\d+)")));
        line(b, "Android alvo", sdk(find(section, "targetSdk=(\\d+)")));
        String flags = find(section, "pkgFlags=\\[([^\\]]*)\\]");
        boolean system = flags != null && flags.contains("SYSTEM");
        line(b, "Tipo", system ? "App do sistema"
                + (flags.contains("UPDATED_SYSTEM_APP") ? " (atualizado)" : "") : "Instalado pelo usuário");
        line(b, "Situação", status(section));
        line(b, "Em execução", pid.isEmpty() ? "não" : "sim (PID " + pid.split("\\s+")[0] + ")");
        line(b, "Tela inicial", launcher.contains("/") && !launcher.contains(" ") ? launcher : "nenhuma (sem ícone)");
        line(b, "Instalado em", find(section, "firstInstallTime=([^\\n]+)"));
        line(b, "Atualizado em", find(section, "lastUpdateTime=([^\\n]+)"));
        String installer = find(section, "installerPackageName=(\\S+)");
        line(b, "Instalado por", installer != null && !installer.equals("null") ? installer
                : system ? "veio no aparelho" : "ADB / desconhecido");
        line(b, "UID", find(section, "(?:userId|appId)=(\\d+)"));
        line(b, "Tamanho do APK", size(sizeKb));
        line(b, "APK", paths.replace("package:", "").replace("\n", "\n      "));
        line(b, "Dados", find(section, "dataDir=(\\S+)"));

        List<String> granted = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        Matcher m = Pattern.compile("^\\s+([\\w.]+): granted=(true|false)", Pattern.MULTILINE).matcher(section);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            if (!seen.add(m.group(1))) continue;
            (m.group(2).equals("true") ? granted : denied).add(shortPermission(m.group(1)));
        }
        Collections.sort(granted);
        Collections.sort(denied);
        b.append("\nPermissões concedidas (").append(granted.size()).append(")\n");
        for (String p : granted) b.append("  • ").append(p).append('\n');
        if (!denied.isEmpty()) {
            b.append("\nPermissões negadas (").append(denied.size()).append(")\n");
            for (String p : denied) b.append("  • ").append(p).append('\n');
        }
        Details details = new Details(pkg, b.toString().trim(), dump);
        details.system = system;
        String enabled = find(section, "User 0:[^\\n]*\\benabled=(\\d)");
        details.enabled = enabled == null || enabled.equals("0") || enabled.equals("1");
        details.running = !pid.isEmpty();
        details.launcher = launcher.contains("/") && !launcher.contains(" ") ? launcher : null;
        return details;
    }

    /** Bloco "Package [pkg]" do dumpsys (até a próxima seção de primeiro nível). */
    private static String packageSection(String dump, String pkg) {
        int start = dump.indexOf("Package [" + pkg + "]");
        if (start < 0) return null;
        Matcher end = Pattern.compile("^\\S", Pattern.MULTILINE).matcher(dump);
        return end.find(start) ? dump.substring(start, end.start()) : dump.substring(start);
    }

    private static String status(String section) {
        String enabled = find(section, "User 0:[^\\n]*\\benabled=(\\d)");
        String stopped = find(section, "User 0:[^\\n]*\\bstopped=(true|false)");
        String hidden = find(section, "User 0:[^\\n]*\\bhidden=(true|false)");
        String suspended = find(section, "User 0:[^\\n]*\\bsuspended=(true|false)");
        List<String> s = new ArrayList<>();
        // enabled: 0 = padrão, 1 = ativado, 2 = desativado, 3 = desativado pelo usuário, 4 = até ser usado.
        s.add("2".equals(enabled) ? "desativado" : "3".equals(enabled) ? "desativado pelo usuário"
                : "4".equals(enabled) ? "desativado até ser usado" : "ativado");
        if ("true".equals(stopped)) s.add("parado à força");
        if ("true".equals(hidden)) s.add("oculto");
        if ("true".equals(suspended)) s.add("suspenso");
        return android.text.TextUtils.join(", ", s);
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String join(String a, String b, String before, String after) {
        if (a == null) return b;
        return b == null ? a : a + before + b + after;
    }

    private static void line(StringBuilder b, String name, String value) {
        if (value != null && !value.isEmpty()) b.append(name).append(": ").append(value).append('\n');
    }

    private static final String[] ANDROID = {
            null, "1.0", "1.1", "1.5", "1.6", "2.0", "2.0.1", "2.1", "2.2", "2.3", "2.3.3", "3.0", "3.1", "3.2",
            "4.0", "4.0.3", "4.1", "4.2", "4.3", "4.4", "4.4W", "5.0", "5.1", "6", "7", "7.1", "8", "8.1", "9",
            "10", "11", "12", "12L", "13", "14", "15", "16"};

    private static String sdk(String api) {
        if (api == null) return null;
        int n = Integer.parseInt(api);
        return n > 0 && n < ANDROID.length ? "Android " + ANDROID[n] + " (API " + n + ")" : "API " + n;
    }

    private static String size(String kb) {
        if (kb == null || !kb.matches("\\d+") || kb.equals("0")) return null;
        double mb = Long.parseLong(kb) / 1024.0;
        return String.format(java.util.Locale.getDefault(), "%.1f MB", mb);
    }

    private static String shortPermission(String p) {
        return p.startsWith("android.permission.") ? p.substring(19) : p;
    }

    private AppInfo() {
    }
}
