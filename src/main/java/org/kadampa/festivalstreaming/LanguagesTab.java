package org.kadampa.festivalstreaming;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Languages tab: which languages this festival streams, and in what order.
 *
 * <p>The order is not decoration. ffmpeg maps the languages that have an audio source, in this
 * order and only those, and the transport stream hands out its packet identifiers in the same
 * order - so moving a language changes the PID and the track number every viewer's player shows
 * for everything below it. That is why each row carries its consequence beside it rather than
 * leaving the operator to work it out.
 *
 * <p>It edits a list of its own and writes it to settings.ini; it never touches
 * {@link Settings#LANGUAGES}. Every per-language control in the window - the audio source combos,
 * the channel combos, the noise reduction combos, the colour pickers, the level meters - was built
 * from that array when the window opened and is indexed in lock-step with it, so growing or
 * reordering it under a running window would at best misalign every setting and at worst index
 * past the end of the combo arrays. Hence the banner: the change is written now and takes effect
 * at the next start.
 */
final class LanguagesTab {

    /** A code is three letters, as ISO 639-2 has it; nothing else reaches ffmpeg intact. */
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{3}");
    /** Long enough for every language anyone has asked for, short enough not to break the grid. */
    private static final int MAX_NAME_LENGTH = 40;
    private static final int UNDO_DEPTH = 20;

    private final Settings settings;
    private final ComboBox<String>[] inputAudioSources;
    private final ComboBox<String>[] inputAudioSourcesChannel;
    private final ReadOnlyBooleanProperty streamAlive;
    /** The one writer for the whole settings file, so both Save buttons take the same path. */
    private final Runnable saveAll;

    private final List<LanguageRow> rows = new ArrayList<>();
    private final Deque<List<RowState>> undoStack = new ArrayDeque<>();
    private final List<Settings.Language> removedThisSession = new ArrayList<>();
    private List<Settings.Language> savedBaseline;

    private final VBox rowsBox = new VBox(2);
    private final VBox editorBox = new VBox(10);
    private final HBox removedBox = new HBox(8);
    private final Label channelOrderHint = new Label();
    private final Label problemsLabel = new Label();
    private final Label savedLabel = new Label();
    private final Button sortByChannel = new Button("⇅ Sort by mixer channel");
    private final Button undo = new Button("↶ Undo");
    private final Button save = new Button("Save languages");
    private final MenuButton addLanguage = new MenuButton("+ Add language");

    /** What a row holds, kept apart from its controls so a snapshot can be taken for undo. */
    private record RowState(String name, String nativeName, String code, int liveIndex, String originalName) { }

    LanguagesTab(Settings settings, ComboBox<String>[] inputAudioSources,
                 ComboBox<String>[] inputAudioSourcesChannel, ReadOnlyBooleanProperty streamAlive,
                 Runnable saveAll) {
        this.settings = settings;
        this.inputAudioSources = inputAudioSources;
        this.inputAudioSourcesChannel = inputAudioSourcesChannel;
        this.streamAlive = streamAlive;
        this.saveAll = saveAll;
    }

    // ---------------------------------------------------------------- building

    Node buildContent() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(14, 10, 14, 10));

        Label restartBanner = new Label("Changes here are written to the settings file and take effect "
                + "the next time the application starts. A running stream keeps the languages it began with.");
        restartBanner.getStyleClass().add("secondary-text");
        restartBanner.setWrapText(true);

        Label lockedBanner = new Label("A stream is running - the language list is locked until it stops.");
        lockedBanner.getStyleClass().add("warning-text");
        lockedBanner.visibleProperty().bind(streamAlive);
        lockedBanner.managedProperty().bind(lockedBanner.visibleProperty());

        for (int i = 0; i < Settings.LANGUAGES.length; i++) {
            rows.add(new LanguageRow(Settings.LANGUAGES[i], i));
        }
        savedBaseline = currentLanguages();

        // The tab is built before the settings are loaded into the combos, and the operator goes on
        // changing them in the Settings tab afterwards. Following them keeps the channel column
        // filled in and the track numbers honest: switching one language off renumbers the rest.
        for (int i = 0; i < inputAudioSources.length; i++) {
            inputAudioSources[i].valueProperty().addListener((observable, oldValue, newValue) -> refresh());
            inputAudioSourcesChannel[i].valueProperty().addListener((observable, oldValue, newValue) -> refresh());
        }

        channelOrderHint.getStyleClass().add("secondary-text");
        channelOrderHint.setWrapText(true);

        sortByChannel.getStyleClass().addAll("event-button", "secondary-button");
        sortByChannel.setTooltip(tooltip("""
                Puts the languages in the order of the mixer channels they are read from, which is the
                order the interpreters sit in and the order the level meters are scanned in.

                It is only a suggestion: the order also decides what a viewer's player lists first, so
                a deliberate order - the biggest audiences at the top - is a perfectly good reason to
                leave it alone. Undo puts it back."""));
        sortByChannel.setOnAction(event -> sortRowsByChannel());

        undo.getStyleClass().addAll("event-button", "secondary-button");
        undo.setOnAction(event -> undoLastChange());

        addLanguage.getStyleClass().addAll("event-button", "primary-button");
        addLanguage.setTooltip(tooltip("""
                A new language is always added at the end. That is the only place it can go without
                renumbering the PID and the track number of a language that is already configured -
                move it up afterwards if you mean to, and you will see those numbers change."""));

        save.getStyleClass().addAll("event-button", "primary-button");
        save.setOnAction(event -> saveLanguages());

        problemsLabel.getStyleClass().add("danger-text");
        problemsLabel.setWrapText(true);
        savedLabel.getStyleClass().add("success-text");
        savedLabel.setWrapText(true);

        HBox toolbar = new HBox(10, addLanguage, spacer(), sortByChannel, undo);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        HBox saveBox = new HBox(save);
        saveBox.setAlignment(Pos.CENTER);
        saveBox.setPadding(new Insets(6, 0, 0, 0));

        editorBox.getChildren().addAll(header(), rowsBox, channelOrderHint, toolbar, removedBox,
                problemsLabel, savedLabel, saveBox);
        // One bind covers the rows, the toolbar and Save: a running stream is already committed to
        // the languages it started with, and every control in here exists to change them
        editorBox.disableProperty().bind(streamAlive);

        content.getChildren().addAll(restartBanner, lockedBanner, editorBox);
        refresh();
        return content;
    }

    private Node header() {
        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(
                headerLabel("#", 26), headerLabel("", 52), headerLabel("Language", 150),
                headerLabel("Native name", 150), headerLabel("Code", 60),
                headerLabel("Channel", 70), headerLabel("Output", 150));
        Label what = new Label("?");
        what.getStyleClass().add("info-for-tooltip");
        Tooltip.install(what, tooltip("""
                The packet identifier and the audio track number a viewer's player will show for each
                language, as things stand. Only a language with an audio source becomes a track, so
                switching one off in the Settings tab renumbers every language below it - and so does
                moving one here."""));
        header.getChildren().add(what);
        header.getStyleClass().add("emphasize");
        return header;
    }

    private Label headerLabel(String text, double width) {
        Label label = new Label(text);
        label.setMinWidth(width);
        label.setPrefWidth(width);
        label.getStyleClass().add("secondary-text");
        return label;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.seconds(0.2));
        tooltip.setShowDuration(Duration.seconds(20));
        tooltip.setHideDelay(Duration.seconds(0.2));
        tooltip.getStyleClass().add("tooltip");
        return tooltip;
    }

    // ------------------------------------------------------------------- rows

    /**
     * One line of the list. It keeps two things the language itself cannot: where it sits in the
     * live {@link Settings#LANGUAGES}, so the row can read the audio source and channel chosen for
     * it in the Settings tab, and the name it was loaded with, so a rename can carry its settings.
     */
    private final class LanguageRow {
        /** Its index in the live LANGUAGES, or -1 for a language added in this session: that one
         *  has no audio source and no level meter until the application is restarted. */
        private final int liveIndex;
        /** The name the row was loaded with; null for a row created here, which has none to keep. */
        private final String originalName;
        private final boolean fixed;
        private final TextField nameField = new TextField();
        private final TextField nativeNameField = new TextField();
        private final TextField codeField = new TextField();
        private final Label position = new Label();
        private final Label channel = new Label();
        private final Label consequence = new Label();
        private final Button up = new Button("↑");
        private final Button down = new Button("↓");
        private final Button remove = new Button("✕");
        private final HBox node = new HBox(6);

        LanguageRow(Settings.Language language, int liveIndex) {
            this.liveIndex = liveIndex;
            this.originalName = language.name();
            this.fixed = liveIndex >= 0 && liveIndex < Settings.fixedLanguages().size();
            build(language);
        }

        LanguageRow(Settings.Language language) {
            this.liveIndex = -1;
            this.originalName = null;
            this.fixed = false;
            build(language);
        }

        private void build(Settings.Language language) {
            nameField.setText(language.name());
            nativeNameField.setText(language.nativeName() == null ? "" : language.nativeName());
            codeField.setText(language.code() == null ? "" : language.code());
            nativeNameField.promptTextProperty().bind(nameField.textProperty());

            sizeField(nameField, 150);
            sizeField(nativeNameField, 150);
            sizeField(codeField, 60);
            for (TextField field : List.of(nameField, nativeNameField, codeField)) {
                field.textProperty().addListener((observable, oldText, newText) -> refresh());
            }

            position.setMinWidth(26);
            position.setPrefWidth(26);
            channel.setMinWidth(70);
            channel.setPrefWidth(70);
            consequence.setMinWidth(150);
            consequence.setPrefWidth(150);

            for (Button button : List.of(up, down, remove)) {
                button.getStyleClass().add("preset-button");
            }
            up.setOnAction(event -> move(this, -1));
            down.setOnAction(event -> move(this, 1));
            remove.setOnAction(event -> removeRow(this));

            HBox moveBox = new HBox(2, up, down);
            moveBox.setMinWidth(52);
            moveBox.setPrefWidth(52);
            moveBox.setAlignment(Pos.CENTER_LEFT);

            node.setAlignment(Pos.CENTER_LEFT);
            node.getStyleClass().add("language-row");
            node.getChildren().addAll(position, moveBox, nameField, nativeNameField, codeField,
                    channel, consequence, remove);

            if (fixed) {
                // Shown, so the numbering means something, but not editable: the prayers and the two
                // English feeds are what every other language is mixed against
                nameField.setEditable(false);
                nativeNameField.setEditable(false);
                codeField.setEditable(false);
                nameField.setMouseTransparent(true);
                nativeNameField.setMouseTransparent(true);
                codeField.setMouseTransparent(true);
                nameField.setFocusTraversable(false);
                nativeNameField.setFocusTraversable(false);
                codeField.setFocusTraversable(false);
                node.getStyleClass().add("language-row-fixed");
                moveBox.getChildren().setAll(lockLabel());
                node.getChildren().remove(remove);
                Tooltip.install(node, tooltip("Built in: the prayers and the two English feeds are"
                        + " what the other languages are mixed against, so they keep the first three"
                        + " places and their names."));
            } else {
                // Alt with the arrows moves the focused row, so the whole list can be ordered from
                // the keyboard; a filter, or the text field under the cursor would swallow it
                node.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                    if (!event.isAltDown()) {
                        return;
                    }
                    switch (event.getCode()) {
                        case UP -> { move(this, -1); event.consume(); }
                        case DOWN -> { move(this, 1); event.consume(); }
                        case HOME -> { moveTo(this, firstConfigurableIndex()); event.consume(); }
                        case END -> { moveTo(this, rows.size() - 1); event.consume(); }
                        default -> { }
                    }
                });
            }
        }

        /** Says why the row has no arrows. A word rather than a padlock glyph, which the
         *  festival machines' fonts do not all carry. */
        private Label lockLabel() {
            Label lock = new Label("fixed");
            lock.getStyleClass().addAll("secondary-text", "small-text");
            lock.setMinWidth(52);
            return lock;
        }

        private void sizeField(TextField field, double width) {
            field.setMinWidth(width);
            field.setPrefWidth(width);
            field.setMaxWidth(width);
        }

        private Settings.Language language() {
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String nativeName = nativeNameField.getText() == null ? "" : nativeNameField.getText().trim();
            String code = codeField.getText() == null ? "" : codeField.getText().trim();
            return new Settings.Language(name, nativeName.isEmpty() ? name : nativeName, code);
        }

        private RowState state() {
            return new RowState(nameField.getText(), nativeNameField.getText(), codeField.getText(),
                    liveIndex, originalName);
        }
    }

    // -------------------------------------------------------------- reordering

    /** The first row that may be moved: the three built-in languages never leave the top. */
    private int firstConfigurableIndex() {
        return Settings.fixedLanguages().size();
    }

    /**
     * Moves one language past its neighbour. The rows keep their nodes rather than being rebuilt:
     * rebuilding would throw the focus away, and keeping it on the button just pressed is what lets
     * a language be walked up the list with repeated clicks instead of one click and a fresh hunt
     * for the arrow.
     */
    private void move(LanguageRow row, int delta) {
        int from = rows.indexOf(row);
        moveTo(row, from + delta);
        (delta < 0 ? row.up : row.down).requestFocus();
    }

    private void moveTo(LanguageRow row, int to) {
        int from = rows.indexOf(row);
        if (from < firstConfigurableIndex() || to < firstConfigurableIndex() || to >= rows.size() || to == from) {
            return;
        }
        pushUndo();
        rows.remove(from);
        rows.add(to, row);
        refresh();
    }

    /**
     * Puts the configurable languages in mixer-channel order. The list order and the desk order
     * drift apart as languages are added over the years, and the desk order is the one the operator
     * works in - it is the order the interpreters sit in and the order the meters are read in.
     * Anything not on a numbered channel keeps its relative place at the end.
     */
    private void sortRowsByChannel() {
        pushUndo();
        List<LanguageRow> configurable = new ArrayList<>(rows.subList(firstConfigurableIndex(), rows.size()));
        configurable.sort((left, right) -> Integer.compare(channelOrder(left), channelOrder(right)));
        rows.subList(firstConfigurableIndex(), rows.size()).clear();
        rows.addAll(configurable);
        refresh();
    }

    /** A numbered channel sorts on its number; everything else sinks to the end, order kept. */
    private int channelOrder(LanguageRow row) {
        String channel = channelOf(row);
        if (channel != null && channel.startsWith("Ch ")) {
            try {
                return Integer.parseInt(channel.substring(3).trim());
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private void pushUndo() {
        savedLabel.setText("");
        if (undoStack.size() == UNDO_DEPTH) {
            undoStack.removeLast();
        }
        undoStack.push(rows.stream().map(LanguageRow::state).toList());
    }

    private void undoLastChange() {
        if (undoStack.isEmpty()) {
            return;
        }
        List<RowState> previous = undoStack.pop();
        rows.clear();
        for (RowState state : previous) {
            Settings.Language language = new Settings.Language(state.name(), state.nativeName(), state.code());
            LanguageRow row = state.liveIndex() >= 0
                    ? new LanguageRow(language, state.liveIndex()) : new LanguageRow(language);
            rows.add(row);
        }
        removedThisSession.removeIf(removed -> rows.stream()
                .anyMatch(row -> row.nameField.getText().equals(removed.name())));
        refresh();
    }

    // ------------------------------------------------------------ add / remove

    private void rebuildAddMenu() {
        addLanguage.getItems().clear();
        Set<String> present = new HashSet<>();
        for (LanguageRow row : rows) {
            present.add(row.nameField.getText().trim().toLowerCase(Locale.ROOT));
        }
        for (Settings.Language preset : LanguagePresets.catalogue()) {
            MenuItem item = new MenuItem(preset.name() + " — " + preset.nativeName() + " (" + preset.code() + ")");
            boolean already = present.contains(preset.name().toLowerCase(Locale.ROOT));
            if (already) {
                // Shown but disabled rather than hidden, so "where is Swedish?" is answered on screen
                item.setText("✓ " + item.getText() + "   already in the list");
                item.setDisable(true);
            } else {
                item.setOnAction(event -> addRow(preset));
            }
            addLanguage.getItems().add(item);
        }
        MenuItem custom = new MenuItem("Custom language…");
        custom.setOnAction(event -> addRow(new Settings.Language("", "", "")));
        addLanguage.getItems().addAll(new SeparatorMenuItem(), custom);
        addLanguage.setDisable(rows.size() - firstConfigurableIndex() >= SettingsUtil.MAX_LANGUAGES);
    }

    private void addRow(Settings.Language language) {
        pushUndo();
        LanguageRow row = new LanguageRow(language);
        rows.add(row);
        removedThisSession.removeIf(removed -> removed.name().equals(language.name()));
        refresh();
        row.nameField.requestFocus();
    }

    private void removeRow(LanguageRow row) {
        pushUndo();
        rows.remove(row);
        removedThisSession.add(row.language());
        refresh();
    }

    // ---------------------------------------------------------------- refresh

    /** Re-lays the rows out and recomputes everything that depends on their order. */
    private void refresh() {
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            LanguageRow row = rows.get(i);
            row.position.setText(String.valueOf(i + 1));
            row.up.setDisable(i <= firstConfigurableIndex());
            row.down.setDisable(i >= rows.size() - 1);
            row.channel.setText(channelOf(row) == null ? "—" : channelOf(row));
            describeOutput(row, i);
            nodes.add(row.node);
        }
        rowsBox.getChildren().setAll(nodes);
        rebuildAddMenu();
        showRemoved();
        showChannelOrderHint();
        undo.setDisable(undoStack.isEmpty());
        String problems = problems();
        problemsLabel.setText(problems);
        save.setDisable(!problems.isEmpty());
    }

    /** What this language becomes in the stream, as the list stands. */
    private void describeOutput(LanguageRow row, int index) {
        row.consequence.getStyleClass().removeAll("secondary-text");
        if (index < 2) {
            row.consequence.setText("mixed in only");
            row.consequence.getStyleClass().add("secondary-text");
            return;
        }
        if (row.liveIndex < 0) {
            row.consequence.setText("not streamed (new)");
            row.consequence.getStyleClass().add("secondary-text");
            return;
        }
        int track = SettingsUtil.audioTrackIndex(index, this::isRowUsed);
        if (track < 0) {
            row.consequence.setText("not streamed");
            row.consequence.getStyleClass().add("secondary-text");
            return;
        }
        row.consequence.setText("PID " + (StreamRecorderRunnable.VIDEO_PID + 1 + track) + " · track " + (track + 1));
    }

    /** Whether the language at this position in the edited list has an audio source. */
    private boolean isRowUsed(int index) {
        if (index < 0 || index >= rows.size()) {
            return false;
        }
        LanguageRow row = rows.get(index);
        return row.liveIndex >= 0
                && !SettingsUtil.AUDIO_SOURCE_NOT_USED.equals(inputAudioSources[row.liveIndex].getValue());
    }

    private String channelOf(LanguageRow row) {
        return row.liveIndex < 0 ? null : inputAudioSourcesChannel[row.liveIndex].getValue();
    }

    private void showRemoved() {
        removedBox.getChildren().clear();
        removedBox.setVisible(!removedThisSession.isEmpty());
        removedBox.setManaged(removedBox.isVisible());
        if (removedThisSession.isEmpty()) {
            return;
        }
        Label caption = new Label("Removed in this session:");
        caption.getStyleClass().add("secondary-text");
        removedBox.getChildren().add(caption);
        for (Settings.Language removed : List.copyOf(removedThisSession)) {
            Button restore = new Button(removed.name() + " ↩");
            restore.getStyleClass().add("preset-button");
            restore.setTooltip(tooltip("Put " + removed.name() + " back at the end of the list."));
            restore.setOnAction(event -> {
                removedThisSession.remove(removed);
                addRow(removed);
            });
            removedBox.getChildren().add(restore);
        }
        removedBox.setAlignment(Pos.CENTER_LEFT);
    }

    /**
     * Says so, quietly, when the list order and the desk order disagree - information rather than
     * a fault, because the order also decides what a viewer's player lists first.
     */
    private void showChannelOrderHint() {
        int previous = -1;
        boolean ordered = true;
        int numbered = 0;
        for (int i = firstConfigurableIndex(); i < rows.size(); i++) {
            int channel = channelOrder(rows.get(i));
            if (channel == Integer.MAX_VALUE) {
                continue;
            }
            numbered++;
            if (channel < previous) {
                ordered = false;
            }
            previous = channel;
        }
        channelOrderHint.setText(ordered || numbered < 2
                ? "" : "This list is not in mixer-channel order. That is only worth changing if you want it to be.");
        channelOrderHint.setVisible(!channelOrderHint.getText().isEmpty());
        channelOrderHint.setManaged(channelOrderHint.isVisible());
        sortByChannel.setDisable(numbered < 2);
    }

    // ------------------------------------------------------------- validation

    /** The first problem standing between the list and the Save button, or "" when there is none. */
    private String problems() {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            LanguageRow row = rows.get(i);
            String name = row.nameField.getText() == null ? "" : row.nameField.getText().trim();
            String code = row.codeField.getText() == null ? "" : row.codeField.getText().trim();
            int line = i + 1;
            if (name.isEmpty()) {
                return "Row " + line + ": the language needs a name.";
            }
            if (name.length() > MAX_NAME_LENGTH) {
                return "Row " + line + ": the name is longer than " + MAX_NAME_LENGTH + " characters.";
            }
            Integer first = seen.putIfAbsent(name.toLowerCase(Locale.ROOT), line);
            if (first != null) {
                return "Row " + line + ": \"" + name + "\" is already the name of row " + first + ".";
            }
            if (!row.fixed && !LANGUAGE_CODE.matcher(code).matches()) {
                return "Row " + line + ": the code must be three lower-case letters, as in \"spa\"."
                        + (code.isEmpty() ? "" : " \"" + code + "\" is not.");
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- saving

    private List<Settings.Language> currentLanguages() {
        return rows.stream().map(LanguageRow::language).toList();
    }


    /** True when the list differs from the one last written, so a caller can warn about it. */
    boolean hasUnsavedEdits() {
        return savedBaseline != null && !savedBaseline.equals(currentLanguages());
    }

    /**
     * Called from the window's own save, after the combos have written the per-language maps and
     * just before the file is written. Late on purpose: that sweep keys the maps by the names in
     * Settings.LANGUAGES, which are still the old ones, so a rename applied any earlier would be
     * written straight back.
     */
    void applyPendingLanguageEdits() {
        Map<String, String> renames = new HashMap<>();
        for (LanguageRow row : rows) {
            String newName = row.language().name();
            if (row.originalName != null && !row.originalName.equals(newName)) {
                renames.put(row.originalName, newName);
            }
        }
        if (!renames.isEmpty()) {
            settings.renameLanguages(renames);
        }
        List<Settings.Language> edited = currentLanguages();
        Set<String> kept = new HashSet<>();
        edited.forEach(language -> kept.add(language.name()));
        settings.forgetLanguagesNotIn(kept);
        settings.setPendingLanguages(edited);
    }

    /** The tab's own Save: it writes the whole settings file, exactly as the Settings tab does. */
    private void saveLanguages() {
        boolean changed = hasUnsavedEdits();
        saveAll.run();
        savedBaseline = currentLanguages();
        removedThisSession.clear();
        undoStack.clear();
        refresh();
        savedLabel.setText(changed
                ? "Saved. The new language list will be used the next time the application starts."
                : "Saved.");
    }
}
