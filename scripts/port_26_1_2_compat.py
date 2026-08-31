from pathlib import Path

ROOT = Path("src/main/java")

REPLACEMENTS = [
    ("net.minecraft.world.entity.EntityTypes", "net.minecraft.world.entity.EntityType"),
    ("EntityTypes.", "EntityType."),
    ("net.minecraft.world.level.block.entity.BlockEntityTypes.", "net.minecraft.world.level.block.entity.BlockEntityType."),
    (".gui.screen()", ".screen"),
    (".gui.setScreen(", ".setScreen("),
    ("mc.gui.toastManager()", "mc.getToastManager()"),
    ("mc.gameRenderer.mainCamera()", "mc.gameRenderer.getMainCamera()"),
    ("team.displayName()", "team.getDisplayName()"),
    ("team.playerPrefix()", "team.getPlayerPrefix()"),
    ("team.playerSuffix()", "team.getPlayerSuffix()"),
    ("Items.COPPER_BLOCK.weathering().unaffected()", "Items.COPPER_BLOCK"),
    ("Items.STAINED_GLASS.pick(DyeColor.WHITE)", "Items.WHITE_STAINED_GLASS"),
    ("import net.minecraft.client.gui.Hud;", "import net.minecraft.client.gui.Gui;"),
    ("@Mixin(Hud.class)", "@Mixin(Gui.class)"),
    ("Lnet/minecraft/client/gui/Hud;extractSlot", "Lnet/minecraft/client/gui/Gui;extractSlot"),
    ("Hud hud, GuiGraphicsExtractor", "Gui gui, GuiGraphicsExtractor"),
    ("original.call(hud, extractor", "original.call(gui, extractor"),
]

changed = []
for path in ROOT.rglob("*.java"):
    original = path.read_text(encoding="utf-8")
    text = original
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)

    # The pluralized 26.2 registry rename can leave duplicate imports in files
    # which already imported EntityType. Keep imports deterministic.
    lines = text.splitlines()
    seen_imports = set()
    out = []
    for line in lines:
        if line.startswith("import "):
            if line in seen_imports:
                continue
            seen_imports.add(line)
        out.append(line)
    text = "\n".join(out) + ("\n" if text.endswith("\n") else "")

    if text != original:
        path.write_text(text, encoding="utf-8")
        changed.append(str(path))

print(f"Updated {len(changed)} Java files for Minecraft 26.1.2")
for path in changed:
    print(path)
