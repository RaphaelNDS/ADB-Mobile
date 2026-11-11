package com.bkp.adbmobile;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/** Roda uma tarefa nos aparelhos marcados ao mesmo tempo e junta o resultado num resumo. */
final class DeviceRunner {

    /** O que fazer em cada aparelho; devolve o resumo para o registro ("" se não houver). */
    interface DeviceTask {
        String run(Devices.Device d) throws Exception;
    }

    private final MainActivity a;

    DeviceRunner(MainActivity activity) {
        a = activity;
    }

    /** Erro do comando em português; troca o texto do Android por uma explicação quando conhecida. */
    static String errorText(Exception e) {
        String friendly = AdbCommands.failure(Adb.describe(e));
        return friendly != null ? friendly : Adb.describe(e);
    }

    /**
     * Roda {@code task} em todos os aparelhos ao mesmo tempo (cada um tem a sua conexão) e
     * termina com um resumo quando o último responder. Com vários aparelhos, o resumo no registro
     * agrupa as falhas pelo motivo e lista o resultado de cada aparelho que deu certo.
     */
    void run(final List<Devices.Device> targets, final String verb, final DeviceTask task) {
        final int total = targets.size();
        final AtomicInteger done = new AtomicInteger();
        // Resultado de cada aparelho, na ordem da lista: motivo da falha (null se deu certo) e saída.
        final String[] errors = new String[total];
        final String[] results = new String[total];
        for (int i = 0; i < total; i++) {
            final int index = i;
            final Devices.Device d = targets.get(i);
            a.background(() -> {
                String prefix = "[" + d.label() + "] ";
                try {
                    results[index] = task.run(d);
                    a.log(prefix + "OK" + (results[index].isEmpty() ? "" : ": " + results[index]));
                } catch (AdbPairingRequiredException e) {
                    errors[index] = MainActivity.NEEDS_PAIRING;
                    a.log(prefix + "ERRO: " + errors[index]);
                } catch (Exception e) {
                    errors[index] = errorText(e);
                    a.log(prefix + "ERRO: " + errors[index]);
                }
                int finished = done.incrementAndGet();
                if (finished < total) {
                    showProgress(done, finished, total);
                    return;
                }
                // O incrementAndGet garante que o último vê os resultados gravados pelos outros.
                finish(targets, verb, errors, results);
            });
        }
    }

    /** "Em andamento... 1 de 3 concluído" no status, enquanto não há outro progresso na tela. */
    private void showProgress(final AtomicInteger done, final int finished, final int total) {
        a.runOnUiThread(() -> {
            // Ignora se a operação já terminou ou se um aviso mais novo já foi mostrado.
            if (a.activeBoard != null || done.get() != finished) return;
            a.setStatus(MainActivity.Status.INFO, "Em andamento... " + finished + " de " + total
                    + (finished == 1 ? " concluído" : " concluídos"));
        });
    }

    private void finish(List<Devices.Device> targets, String verb, String[] errors, String[] results) {
        int total = targets.size();
        List<String> unpaired = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            if (MainActivity.NEEDS_PAIRING.equals(errors[i])) unpaired.add(targets.get(i).label());
        }
        if (!unpaired.isEmpty()) {
            final String names = TextUtils.join(", ", unpaired);
            a.runOnUiThread(() -> a.showPairingDialog(names));
        }
        if (total == 1) {
            String label = targets.get(0).label();
            if (errors[0] != null) a.finish(MainActivity.Status.ERROR, "Falha em " + label + ": " + errors[0]);
            else a.finish(MainActivity.Status.SUCCESS,
                    results[0].isEmpty() ? verb + " em " + label + "." : label + ": " + results[0]);
            return;
        }
        // Falhas agrupadas pelo motivo (o mesmo erro em vários aparelhos aparece uma vez só).
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        List<String> okPlain = new ArrayList<>();
        List<String> okWithResult = new ArrayList<>();
        List<String> failedLabels = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            String label = targets.get(i).label();
            if (errors[i] != null) {
                failedLabels.add(label);
                List<String> list = byReason.get(errors[i]);
                if (list == null) byReason.put(errors[i], list = new ArrayList<>());
                list.add(label);
            } else if (results[i].isEmpty()) {
                okPlain.add(label);
            } else {
                okWithResult.add(label + ": " + results[i]);
            }
        }
        int ok = total - failedLabels.size();
        StringBuilder summary = new StringBuilder("Resumo: " + ok + " de " + total + " OK");
        if (!failedLabels.isEmpty()) summary.append(", ").append(failedLabels.size()).append(" com falha");
        for (Map.Entry<String, List<String>> e : byReason.entrySet()) {
            summary.append("\n  FALHA (").append(e.getValue().size()).append("): ")
                    .append(TextUtils.join(", ", e.getValue())).append("\n    ").append(e.getKey());
        }
        if (!okPlain.isEmpty()) {
            summary.append("\n  OK (").append(okPlain.size()).append("): ").append(TextUtils.join(", ", okPlain));
        }
        for (String line : okWithResult) summary.append("\n  OK ").append(line);
        a.log(summary.toString());

        if (failedLabels.isEmpty()) {
            a.finish(MainActivity.Status.SUCCESS, verb + " nos " + total + " aparelhos.");
        } else if (byReason.size() == 1) {
            a.finish(MainActivity.Status.ERROR, "OK em " + ok + " de " + total + ". Falhou em "
                    + TextUtils.join(", ", failedLabels) + ": " + byReason.keySet().iterator().next());
        } else {
            a.finish(MainActivity.Status.ERROR, "OK em " + ok + " de " + total + ". Falhou em "
                    + TextUtils.join(", ", failedLabels) + " (motivos no resumo do registro).");
        }
    }
}
