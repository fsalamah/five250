package com.acabes.five250;

import org.tn5250j.keyboard.KeyMnemonic;

import java.util.List;
import java.util.Map;

/**
 * Types a CLI command at the IBM i Main Menu command line, presses Enter, checks the
 * resulting screen's title line, then presses F3 to return to the Main Menu so the next
 * row can run immediately. Assumes the session is already sitting on the Main Menu.
 */
public final class RunCommandFlow implements Flow {

    // Verified live against pub400.com: "Selection or command ===>" prompt sits here.
    private static final int CMD_ROW = 20;
    private static final int CMD_COL = 7;

    @Override
    public String name() {
        return "run-command";
    }

    @Override
    public List<String> csvColumns() {
        return List.of("command", "expectedTitleContains");
    }

    @Override
    public ScenarioResult run(Terminal t, Map<String, String> row) {
        ScenarioResult result = new ScenarioResult(row);
        try {
            String command = row.getOrDefault("command", "");
            String expectedTitle = row.getOrDefault("expectedTitleContains", "");

            Progress.report("running: " + command);
            t.typeAt(CMD_ROW, CMD_COL, command);
            t.sendKey(KeyMnemonic.ENTER);
            t.waitReady(8000);
            result.step("submitted: " + command, t.snapshot());

            String actualTitle = t.rowText(1);
            boolean pass = expectedTitle.isBlank()
                || actualTitle.toUpperCase().contains(expectedTitle.toUpperCase());
            result.check("title", expectedTitle, actualTitle, pass);
            if (!pass) {
                result.screenOnFailure = t.snapshot();
            }

            t.sendKey(KeyMnemonic.PF3);
            t.waitReady(5000);
            result.step("returned to menu", t.snapshot());
        } catch (Exception e) {
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        return result;
    }
}
