package com.bkp.adbmobile;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Aparelhos remotos salvos (nome, IP, porta e se está marcado), guardados nas preferências. */
public final class Devices {

    private static final String KEY = "devices";

    public static final class Device {
        public String name;
        public final String host;
        public int port;
        public boolean selected;

        public Device(String name, String host, int port, boolean selected) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.selected = selected;
        }

        /** Nome para o registro: o nome dado ou, sem ele, o IP. */
        public String label() {
            return name == null || name.isEmpty() ? host : name;
        }

        public String address() {
            return host + ":" + port;
        }
    }

    private Devices() {
    }

    /**
     * Lê a lista. Na primeira vez, transforma o IP/porta únicos das versões anteriores
     * ("host"/"port") no primeiro aparelho, já marcado.
     */
    public static synchronized List<Device> load(SharedPreferences prefs) {
        List<Device> list = new ArrayList<>();
        String json = prefs.getString(KEY, null);
        if (json == null) {
            String host = prefs.getString("host", "").trim();
            int port = parsePort(prefs.getString("port", ""));
            if (!host.isEmpty() && port > 0) list.add(new Device("", host, port, true));
            save(prefs, list);
            return list;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                list.add(new Device(o.optString("name", ""), o.getString("host"),
                        o.getInt("port"), o.optBoolean("selected", false)));
            }
        } catch (Exception ignored) {
            // Lista corrompida: começa vazia em vez de travar a tela.
        }
        return list;
    }

    public static synchronized void save(SharedPreferences prefs, List<Device> list) {
        JSONArray array = new JSONArray();
        try {
            for (Device d : list) {
                array.put(new JSONObject().put("name", d.name).put("host", d.host)
                        .put("port", d.port).put("selected", d.selected));
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }

    /** Aparelho com este IP, ou null. O IP identifica o aparelho (a porta muda). */
    public static Device find(List<Device> list, String host) {
        for (Device d : list) if (d.host.equals(host)) return d;
        return null;
    }

    public static List<Device> selected(List<Device> list) {
        List<Device> out = new ArrayList<>();
        for (Device d : list) if (d.selected) out.add(d);
        return out;
    }

    /** A Depuração sem fio do aparelho reiniciou com outra porta: atualiza o salvo. */
    public static synchronized void updatePort(SharedPreferences prefs, String host, int port) {
        List<Device> list = load(prefs);
        Device d = find(list, host);
        if (d == null || d.port == port) return;
        d.port = port;
        save(prefs, list);
    }

    /** Porta válida (1-65535) ou -1. */
    public static int parsePort(String text) {
        try {
            int p = Integer.parseInt(text.trim());
            return p > 0 && p <= 65535 ? p : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}
