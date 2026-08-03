package com.acabes.five250;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates a suite CSV's optional "id" column (see GenericStepFlow's column docs and
 * JsSuiteRunner's {@code .steps(a, b)}) - every non-blank id must be unique across the whole
 * file, since a script addresses a range by id with no other disambiguation. Checked at two
 * points, both surfacing the exact same message:
 *   - HttpApi's PUT /api/scenarios (the Suites tab's Save button) - the primary point, so a
 *     duplicate is caught and clearly reported the moment it's introduced, regardless of whether
 *     any script ever imports this suite.
 *   - JsSuiteRunner's importSuite() - a safety net for a file that reached disk some other way
 *     (hand-edited, copied in, written by an older build before this check existed).
 */
final class StepIds {

    private StepIds() {}

    /** @throws RuntimeException naming the duplicated id and both its 1-based row positions in
     *          the file, if any non-blank "id" value appears more than once. */
    static void validateUnique(List<Map<String, String>> rows) {
        Map<String, Integer> seenAt = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String id = rows.get(i).getOrDefault("id", "").trim();
            if (id.isEmpty()) continue;
            Integer firstRow = seenAt.get(id);
            if (firstRow != null) {
                throw new RuntimeException("duplicate step id '" + id + "' at row " + firstRow
                    + " and row " + (i + 1) + " - step ids must be unique within a suite file");
            }
            seenAt.put(id, i + 1);
        }
    }
}
