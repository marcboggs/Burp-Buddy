package ui.settings;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * "Allowed MCP tools" picker: Load Tools / Select All / Select None plus a
 * checkbox per tool. The selection is stored as a comma-separated string via
 * the given getter/setter; every tick saves immediately.
 */
final class ToolPickerPanel extends JPanel {
    private final Supplier<String> savedTools;
    private final Consumer<String> saveTools;
    private final Callable<List<String>> loader;
    private final Supplier<String> precheck;
    private final JPanel list = new JPanel();
    private final List<JCheckBox> boxes = new ArrayList<>();

    /**
     * @param loader   lists the available tools; runs off the EDT
     * @param precheck returns an error message to show instead of loading, or null to go ahead
     */
    ToolPickerPanel(Supplier<String> savedTools, Consumer<String> saveTools,
                    Callable<List<String>> loader, Supplier<String> precheck) {
        this.savedTools = savedTools;
        this.saveTools = saveTools;
        this.loader = loader;
        this.precheck = precheck;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
        row.add(new JLabel("Allowed MCP tools:"));
        JButton loadButton = new JButton("Load Tools");
        loadButton.addActionListener(e -> load(loadButton));
        row.add(loadButton);
        JButton allButton = new JButton("Select All");
        allButton.addActionListener(e -> setAll(true));
        row.add(allButton);
        JButton noneButton = new JButton("Select None");
        noneButton.addActionListener(e -> setAll(false));
        row.add(noneButton);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(row);

        JLabel warning = new JLabel("Prompts include untrusted HTTP traffic; ticked tools can be triggered by it.");
        warning.setFont(warning.getFont().deriveFont(Font.ITALIC, 11f));
        warning.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        warning.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(warning);

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(500, 160));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(scroll);

        // Show what's saved until the user loads the live list.
        show(List.of());
    }

    private Set<String> saved() {
        Set<String> saved = new LinkedHashSet<>();
        String raw = savedTools.get();
        if (raw != null) {
            for (String t : raw.split(",")) {
                if (!t.isBlank()) saved.add(t.trim());
            }
        }
        return saved;
    }

    /** Rebuilds the checkboxes from loaded tools plus any saved ones not in it. */
    private void show(List<String> loaded) {
        Set<String> saved = saved();
        Set<String> all = new TreeSet<>(loaded);
        all.addAll(saved);

        list.removeAll();
        boxes.clear();
        if (all.isEmpty()) {
            list.add(new JLabel(" No MCP tools allowed. Click Load Tools to list them."));
        }
        for (String name : all) {
            JCheckBox box = new JCheckBox(name, saved.contains(name));
            box.addActionListener(e -> save());
            boxes.add(box);
            list.add(box);
        }
        list.revalidate();
        list.repaint();
    }

    private void setAll(boolean selected) {
        boxes.forEach(b -> b.setSelected(selected));
        save();
    }

    private void save() {
        saveTools.accept(String.join(",", boxes.stream()
                .filter(AbstractButton::isSelected).map(AbstractButton::getText).toList()));
    }

    private void load(JButton button) {
        String problem = precheck.get();
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, "Cannot Load Tools", JOptionPane.WARNING_MESSAGE);
            return;
        }
        button.setEnabled(false);
        button.setText("Loading...");

        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return loader.call();
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                button.setText("Load Tools");
                try {
                    List<String> tools = get();
                    show(tools);
                    if (tools.isEmpty()) {
                        JOptionPane.showMessageDialog(ToolPickerPanel.this,
                                "No connected MCP tools were reported.\n\n"
                                        + "Check that the MCP servers are configured and running.",
                                "No MCP Tools", JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(ToolPickerPanel.this,
                            "Failed to load MCP tools:\n" + cause.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
