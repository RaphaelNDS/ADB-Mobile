package com.bkp.adbmobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Comandos salvos pela pessoa ("Meus comandos"), na ordem em que foram criados. Ficam nas
 * preferências do app como uma lista JSON de {nome, comando, confirmar}.
 */
public final class SavedCommands {

    private static final String PREFS = "comandos";
    private static final String KEY = "lista";

    private final SharedPreferences prefs;

    public SavedCommands(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<AdbCommands.Preset> load() {
        List<AdbCommands.Preset> list = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                list.add(AdbCommands.saved(o.getString("nome"), o.getString("comando"),
                        o.optBoolean("confirmar", false)));
            }
        } catch (JSONException e) {
            // Lista corrompida: começa vazia em vez de impedir o app de abrir.
        }
        return list;
    }

    /**
     * Salva o comando. Se já existe um com o mesmo nome, ele é substituído no mesmo lugar da
     * lista; senão, entra no fim. Devolve true se substituiu.
     */
    public boolean put(String name, String command, boolean confirm) {
        List<AdbCommands.Preset> list = load();
        AdbCommands.Preset item = AdbCommands.saved(name, command, confirm);
        int at = indexOf(list, name);
        if (at >= 0) list.set(at, item);
        else list.add(item);
        store(list);
        return at >= 0;
    }

    public void remove(String name) {
        List<AdbCommands.Preset> list = load();
        int at = indexOf(list, name);
        if (at < 0) return;
        list.remove(at);
        store(list);
    }

    public boolean contains(String name) {
        return indexOf(load(), name) >= 0;
    }

    private static int indexOf(List<AdbCommands.Preset> list, String name) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).label.equals(name)) return i;
        }
        return -1;
    }

    private void store(List<AdbCommands.Preset> list) {
        JSONArray array = new JSONArray();
        try {
            for (AdbCommands.Preset p : list) {
                array.put(new JSONObject()
                        .put("nome", p.label)
                        .put("comando", p.command)
                        .put("confirmar", p.confirm));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs.edit().putString(KEY, array.toString()).apply();
    }
}
