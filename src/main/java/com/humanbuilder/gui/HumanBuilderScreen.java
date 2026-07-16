package com.humanbuilder.gui;

import com.humanbuilder.HumanBuilderMod;
import com.humanbuilder.command.BuildCommand;
import com.humanbuilder.executor.BuildExecutor;
import com.humanbuilder.executor.BuildState;
import com.humanbuilder.logic.SortMode;
import com.humanbuilder.parser.SchematicParser;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HumanBuilderScreen extends Screen {

    private static String lastFilename = "";

    private final BuildExecutor executor;
    private TextFieldWidget filenameField;
    private ButtonWidget previewButton;
    private ButtonWidget buildButton;
    private ButtonWidget pauseButton;
    private ButtonWidget stopButton;
    private ButtonWidget modeButton;
    private ButtonWidget hologramButton;
    private final List<ButtonWidget> originButtons = new ArrayList<>();
    private String statusMessage = "H: открыть панель, Esc: закрыть";
    private int panelLeft;
    private int panelTop;
    private int panelWidth;

    public HumanBuilderScreen(BuildExecutor executor) {
        super(Text.literal("HumanBuilder Control Deck"));
        this.executor = executor;
    }

    @Override
    protected void init() {
        originButtons.clear();
        panelWidth = Math.min(460, width - 20);
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(4, (height - 236) / 2);
        int contentLeft = panelLeft + 12;
        int contentWidth = panelWidth - 24;

        filenameField = new TextFieldWidget(textRenderer, contentLeft, panelTop + 30,
                contentWidth, 20, Text.literal("Файл схемы"));
        filenameField.setMaxLength(260);
        filenameField.setPlaceholder(Text.literal("house.litematic / build.schem"));
        filenameField.setText(lastFilename);
        filenameField.setChangedListener(value -> lastFilename = value);
        addDrawableChild(filenameField);

        int half = (contentWidth - 6) / 2;
        previewButton = addButton("Предпросмотр", contentLeft, panelTop + 55, half,
                () -> loadSchematic(false));
        buildButton = addButton("Собрать сейчас", contentLeft + half + 6, panelTop + 55, half,
                () -> loadSchematic(true));

        pauseButton = addButton("Пауза", contentLeft, panelTop + 105, half, this::togglePause);
        stopButton = addButton("Стоп", contentLeft + half + 6, panelTop + 105, half,
                executor::stopBuild);

        modeButton = addButton("Режим", contentLeft, panelTop + 130, half, this::cycleMode);
        hologramButton = addButton("Голограмма", contentLeft + half + 6, panelTop + 130,
                half, this::toggleHologram);

        int controlsLeft = contentLeft;
        int gap = 4;
        int moveWidth = (contentWidth - gap * 5) / 6;
        originButtons.add(addButton("X-", controlsLeft, panelTop + 160, moveWidth,
                () -> executor.moveSchematic(-1, 0, 0)));
        originButtons.add(addButton("X+", controlsLeft + (moveWidth + gap), panelTop + 160, moveWidth,
                () -> executor.moveSchematic(1, 0, 0)));
        originButtons.add(addButton("Y-", controlsLeft + (moveWidth + gap) * 2, panelTop + 160, moveWidth,
                () -> executor.moveSchematic(0, -1, 0)));
        originButtons.add(addButton("Y+", controlsLeft + (moveWidth + gap) * 3, panelTop + 160, moveWidth,
                () -> executor.moveSchematic(0, 1, 0)));
        originButtons.add(addButton("Z-", controlsLeft + (moveWidth + gap) * 4, panelTop + 160, moveWidth,
                () -> executor.moveSchematic(0, 0, -1)));
        originButtons.add(addButton("Z+", controlsLeft + (moveWidth + gap) * 5, panelTop + 160, moveWidth,
                () -> executor.moveSchematic(0, 0, 1)));

        addButton("Закрыть", contentLeft, panelTop + 190, contentWidth, this::close);
        refreshButtons();
    }

    private ButtonWidget addButton(String label, int x, int y, int buttonWidth, Runnable action) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), ignored -> action.run())
                .dimensions(x, y, buttonWidth, 20)
                .build();
        return addDrawableChild(button);
    }

    @Override
    public void tick() {
        super.tick();
        refreshButtons();
    }

    private void refreshButtons() {
        if (pauseButton == null) return;
        BuildState state = executor.getState();
        pauseButton.setMessage(Text.literal(state == BuildState.PAUSED ? "Продолжить" : "Пауза"));
        pauseButton.active = state != BuildState.IDLE && state != BuildState.PREVIEW;
        boolean canLoad = state == BuildState.IDLE || state == BuildState.PREVIEW;
        previewButton.active = canLoad;
        buildButton.active = canLoad;
        stopButton.active = state != BuildState.IDLE;
        modeButton.active = canLoad;
        for (ButtonWidget button : originButtons) button.active = state == BuildState.PREVIEW;
        modeButton.setMessage(Text.literal("Режим: " + executor.getSortMode().name()));
        hologramButton.setMessage(Text.literal(
                "Голограмма: " + (executor.isHologramVisible() ? "ВКЛ" : "ВЫКЛ")));
    }

    private void loadSchematic(boolean startImmediately) {
        if (executor.isActive() && executor.getState() != BuildState.PREVIEW) {
            statusMessage = "Сначала остановите текущую постройку";
            return;
        }
        String filename = filenameField.getText().trim();
        if (filename.isEmpty()) {
            statusMessage = "Укажите имя файла из папки schematics";
            return;
        }

        File file = BuildCommand.resolveSchematicFile(filename);
        if (file == null || !file.exists()) {
            statusMessage = "Файл не найден: " + filename;
            return;
        }

        try {
            Map<BlockPos, BlockState> blocks = new SchematicParser().parse(file);
            if (blocks.isEmpty()) {
                statusMessage = "Схема не содержит блоков";
                return;
            }
            executor.loadSchematic(blocks);
            if (startImmediately) executor.startBuild();
            statusMessage = "Загружено блоков: " + blocks.size();
        } catch (Exception exception) {
            statusMessage = "Ошибка: " + exception.getMessage();
            HumanBuilderMod.LOGGER.error("Failed to load schematic from GUI", exception);
        }
    }

    private void togglePause() {
        if (executor.getState() == BuildState.PAUSED) executor.resumeBuild();
        else executor.pauseBuild();
    }

    private void cycleMode() {
        SortMode next = switch (executor.getSortMode()) {
            case LAYERED -> SortMode.MIXED;
            case MIXED -> SortMode.DEFAULT;
            case DEFAULT -> SortMode.LAYERED;
        };
        executor.setSortMode(next);
    }

    private void toggleHologram() {
        executor.setHologramVisible(!executor.isHologramVisible());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // The overridden renderBackground is intentionally a no-op. Screen.render
        // calls it again from super.render(), so merely omitting our own call is
        // not enough to prevent Minecraft's blur shader.
        context.fill(0, 0, width, height, 0x52070D0F);
        int right = panelLeft + panelWidth;
        context.fill(panelLeft, panelTop, right, panelTop + 222, 0xE6121A1D);
        context.fill(panelLeft, panelTop, right, panelTop + 3, 0xFF35D0BA);
        context.fill(panelLeft + 1, panelTop + 3, panelLeft + 4, panelTop + 222, 0xFFCA8A35);

        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, panelTop + 10, 0xFFF4EEDC);
        context.drawTextWithShadow(textRenderer, Text.literal(statusLine()), panelLeft + 12,
                panelTop + 81, 0xFFB9C6C3);
        drawProgress(context, panelLeft + 12, panelTop + 94, panelWidth - 24);
        context.drawTextWithShadow(textRenderer, Text.literal("Сдвиг голограммы:"), panelLeft + 12,
                panelTop + 150, 0xFFE4B86A);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(statusMessage), width / 2,
                panelTop + 214, 0xFF87DDD0);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Keep the live world sharp; render() supplies the lightweight dim layer.
    }

    private String statusLine() {
        BuildState state = executor.getState();
        String target = executor.getCurrentEntry() == null
                ? "нет"
                : executor.getCurrentEntry().pos().toShortString();
        return "Состояние: " + state.name() + "   Цель: " + target;
    }

    private void drawProgress(DrawContext context, int x, int y, int barWidth) {
        int total = executor.getTotalBlocks();
        int placed = executor.getBlocksPlaced();
        float progress = total <= 0 ? 0.0f : Math.min(1.0f, (float) placed / total);
        context.fill(x, y, x + barWidth, y + 7, 0xFF273437);
        context.fill(x, y, x + Math.round(barWidth * progress), y + 7, 0xFF35D0BA);
        context.drawTextWithShadow(textRenderer,
                Text.literal(placed + " / " + total), x + barWidth - 58, y - 10, 0xFFD7E0DE);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
