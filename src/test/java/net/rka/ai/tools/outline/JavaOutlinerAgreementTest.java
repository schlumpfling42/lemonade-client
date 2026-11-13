package net.rka.ai.tools.outline;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sections come from the file's structure; the older scan of the text must find the same sections in every Java file of the project
 * (the ids and lines that {@code editSection} callers already use).
 */
class JavaOutlinerAgreementTest {

    @Test
    void theStructureAndTheScanFindTheSameSectionsInEveryFileOfTheProject() throws Exception {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src"))) {
            walk.filter(p -> p.toString().endsWith(".java") && !p.toString().endsWith("_output.java")).forEach(files::add);
        }
        List<String> differences = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file);
            List<String> model = lines(new JavaOutliner().sections(text, text.lines().toList()));
            // the scan names the record components of an annotation or the like \`?\`: nothing a person could name
            List<String> scan = lines(JavaOutliner.scanOnly(text)).stream().filter(l -> !l.contains(".? field")).toList();
            if (!model.equals(scan)) {
                List<String> onlyModel = new ArrayList<>(model);
                onlyModel.removeAll(scan);
                List<String> onlyScan = new ArrayList<>(scan);
                onlyScan.removeAll(model);
                differences.add(file + "\n  model only: " + onlyModel + "\n  scan only:  " + onlyScan);
            }
        }
        assertTrue(files.size() > 100);
        assertEquals(List.of(), differences);
    }

    private static List<String> lines(List<Section> sections) {
        return sections.stream().map(s -> s.id() + " " + s.kind() + " " + s.start() + "-" + s.end()).toList();
    }
}
