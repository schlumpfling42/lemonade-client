package net.rka.ai.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import net.rka.ai.agent.skills.SkillFile;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Renders a small sample skill's node graph with {@link DiagramView} and writes it to disk as
 * {@code diagram_example.png}, so the rendered flowchart can be opened and viewed.
 */
public final class DiagramExample {

    private DiagramExample() {
    }

    public static void main(String[] args) throws Exception {
        SkillFile skill = sampleSkill();
        File out = new File("diagram_example.png");

        // DiagramView builds a JavaFX ImageView, which must happen on the FX thread.
        Platform.startup(() -> {});
        try {
            renderAndSave(skill, out);
        } finally {
            Platform.exit();
        }

        System.out.println("Wrote " + out.getAbsolutePath());
    }

    private static void renderAndSave(SkillFile skill, File out) throws Exception {
        ImageView[] rendered = new ImageView[1]; // assigned inside the lambda, so a holder, not a local variable
        Throwable[] error = new Throwable[1];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                rendered[0] = DiagramView.render(skill);
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });
        latch.await();
        if (error[0] != null) {
            throw new Exception("render failed", error[0]);
        }

        Image image = rendered[0].getImage();
        java.awt.image.BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        ImageIO.write(buffered, "png", out);
    }

    private static SkillFile sampleSkill() {
        List<SkillFile.NodeSpec> nodes = List.of(
                new SkillFile.NodeSpec("collect", "model", Map.of("prompt", "Gather the changes"), "draft", Map.of()),
                new SkillFile.NodeSpec("draft", "model", Map.of("prompt", "Draft the notes"), "check", Map.of()),
                new SkillFile.NodeSpec("check", "askUser", Map.of(), null,
                        Map.of("accept", "publish", "revise", "draft")),
                new SkillFile.NodeSpec("publish", "model", Map.of("prompt", "Publish the notes"), null, Map.of()),
                new SkillFile.NodeSpec("report", "model", Map.of("prompt", "Summarize the run"), null, Map.of()));
        return new SkillFile(
                "release-notes",
                null,
                "Draft release notes from recent changes and publish them.",
                List.of(new SkillFile.Input("since", "tag or date to start from", false)),
                new SkillFile.Limits(40, 8, Map.of()),
                "collect",
                nodes);
    }
}
