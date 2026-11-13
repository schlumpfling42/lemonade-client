package net.rka.ai.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.List;
import net.rka.ai.agent.skills.SkillFile;

/**
 * Renders a skill's node graph as a flowchart image using Java2D. Must be called on the FX thread.
 */
public final class DiagramView {

    private static final int NODE_WIDTH = 160;
    private static final int NODE_HEIGHT = 48;
    private static final int H_GAP = 40;
    private static final int V_GAP = 32;
    private static final Color BG_COLOR = new Color(245, 245, 245);
    private static final Color NODE_FILL = new Color(220, 230, 255);
    private static final Color EDGE_COLOR = new Color(80, 80, 80);
    private static final Color TEXT_COLOR = new Color(30, 30, 30);

    private DiagramView() {
    }

    /**
     * Draws the skill's nodes and edges into a JavaFX {@link ImageView}.
     *
     * @param skill the skill file whose graph to render
     * @return an ImageView showing the flowchart
     */
    public static ImageView render(SkillFile skill) {
        List<SkillFile.NodeSpec> nodes = skill.nodes();
        if (nodes.isEmpty()) {
            return new ImageView((Image) null);
        }

        int rows = (int) Math.ceil((double) nodes.size() / 3);
        int cols = 3;
        int width = cols * NODE_WIDTH + (cols - 1) * H_GAP + 2 * H_GAP;
        int height = rows * NODE_HEIGHT + (rows - 1) * V_GAP + 2 * V_GAP;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(BG_COLOR);
        g.fillRect(0, 0, width, height);

        for (int i = 0; i < nodes.size(); i++) {
            int row = i / cols;
            int col = i % cols;
            int x = H_GAP + col * (NODE_WIDTH + H_GAP);
            int y = V_GAP + row * (NODE_HEIGHT + V_GAP);
            drawNode(g, nodes.get(i), x, y);

            if (i < nodes.size() - 1) {
                drawEdge(g, x, y, nextPosition(nodes.size(), i + 1, cols));
            }
        }

        g.dispose();
        return new ImageView(toJavaFxImage(image));
    }

    private static void drawNode(Graphics2D g, SkillFile.NodeSpec node, int x, int y) {
        GeneralPath path = roundedRect(x, y, NODE_WIDTH, NODE_HEIGHT, 8);
        g.setColor(NODE_FILL);
        g.fill(path);
        g.setColor(EDGE_COLOR);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(path);

        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(TEXT_COLOR);
        String label = truncate(node.id(), 20);
        int textX = x + NODE_WIDTH / 2;
        int textY = y + NODE_HEIGHT / 2 + 4;
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.drawString(label, textX - fm.stringWidth(label) / 2, textY);
    }

    private static void drawEdge(Graphics2D g, int fromX, int fromY, int[] toPos) {
        int startX = fromX + NODE_WIDTH;
        int startY = fromY + NODE_HEIGHT / 2;
        int endX = toPos[0];
        int endY = toPos[1] + NODE_HEIGHT / 2;

        g.setColor(EDGE_COLOR);
        g.setStroke(new BasicStroke(1.5f));
        GeneralPath edge = new GeneralPath();
        edge.moveTo(startX, startY);
        int midX = (startX + endX) / 2;
        edge.curveTo(midX, startY, midX, endY, endX, endY);
        g.draw(edge);

        drawArrowhead(g, endX, endY);
    }

    private static void drawArrowhead(Graphics2D g, int x, int y) {
        GeneralPath arrow = new GeneralPath();
        arrow.moveTo(x, y);
        arrow.lineTo(x - 8, y - 5);
        arrow.lineTo(x - 8, y + 5);
        arrow.closePath();
        g.setColor(EDGE_COLOR);
        g.fill(arrow);
    }

    private static int[] nextPosition(int total, int index, int cols) {
        int row = index / cols;
        int col = index % cols;
        return new int[]{H_GAP + col * (NODE_WIDTH + H_GAP), V_GAP + row * (NODE_HEIGHT + V_GAP)};
    }

    private static GeneralPath roundedRect(int x, int y, int w, int h, int arc) {
        GeneralPath path = new GeneralPath();
        path.moveTo(x + arc, y);
        path.lineTo(x + w - arc, y);
        path.quadTo(x + w, y, x + w, y + arc);
        path.lineTo(x + w, y + h - arc);
        path.quadTo(x + w, y + h, x + w - arc, y + h);
        path.lineTo(x + arc, y + h);
        path.quadTo(x, y + h, x, y + h - arc);
        path.lineTo(x, y + arc);
        path.quadTo(x, y, x + arc, y);
        path.closePath();
        return path;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }

    private static Image toJavaFxImage(BufferedImage bi) {
        byte[] bytes = new byte[bi.getWidth() * bi.getHeight() * 4];
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) {
                int argb = bi.getRGB(x, y);
                int idx = (y * bi.getWidth() + x) * 4;
                bytes[idx] = (byte) (argb >> 16 & 0xFF);
                bytes[idx + 1] = (byte) (argb >> 8 & 0xFF);
                bytes[idx + 2] = (byte) (argb & 0xFF);
                bytes[idx + 3] = (byte) (argb >> 24 & 0xFF);
            }
        }
        return new Image(new java.io.ByteArrayInputStream(bytes), bi.getWidth(), bi.getHeight(), false, true);
    }
}
