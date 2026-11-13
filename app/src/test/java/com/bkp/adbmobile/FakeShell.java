package com.bkp.adbmobile;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Shell de mentira para os testes: devolve as respostas na ordem e guarda os comandos recebidos. */
class FakeShell implements Adb.Shell {

    final List<String> commands = new ArrayList<>();
    private final String[] replies;
    private int next;

    FakeShell(String... replies) {
        this.replies = replies;
    }

    @Override
    public String run(String command) {
        commands.add(command);
        return next < replies.length ? replies[next++] : "";
    }

    @Override
    public void stream(String command, OutputStream out) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void follow(String command, OutputStream out, Adb.Stopper stopper) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String install(File apk, Adb.Progress progress) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void push(File file, String remotePath, Adb.Progress progress) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long pull(String remotePath, OutputStream out, Adb.Progress progress) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
    }
}
