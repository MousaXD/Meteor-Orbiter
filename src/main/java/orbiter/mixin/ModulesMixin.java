package orbiter.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.datafixers.util.Pair;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import orbiter.Orbiter;
import orbiter.util.ConfigModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Mixin(value = Modules.class, priority = 500)
public abstract class ModulesMixin {
    /*
     * Meteor runs searchSettingTitles synchronously on every search-box keystroke and its
     * stock implementation allocates a full Levenshtein matrix for every setting title.
     * Orbiter adds enough settings for that allocation storm to become visible as GUI lag.
     *
     * Setting titles are effectively immutable after a module is constructed, so cache their
     * lowercase form once and use the same weighted Levenshtein costs with a reusable two-row
     * workspace. This preserves Meteor's fuzzy ranking while removing almost all per-keystroke
     * allocation pressure.
     */
    private static final Map<Module, String[]> ORBITER_SETTING_TITLE_CACHE = new IdentityHashMap<>();

    private static final class HiddenCategories {
        final boolean stupid;
        final boolean wip;

        HiddenCategories(boolean stupid, boolean wip) {
            this.stupid = stupid;
            this.wip = wip;
        }

        boolean hides(Category category) {
            return (category == Orbiter.CATEGORY_STUPID && stupid)
                || (category == Orbiter.CATEGORY_WIP && wip);
        }
    }

    private static HiddenCategories orbiter$hiddenCategories() {
        ConfigModifier config = ConfigModifier.get();
        return new HiddenCategories(!config.stupidModulesEnabled(), !config.wipModulesEnabled());
    }

    private static String[] orbiter$settingTitles(Module module) {
        String[] cached = ORBITER_SETTING_TITLE_CACHE.get(module);
        if (cached != null) return cached;

        List<String> titles = new ArrayList<>();
        for (SettingGroup group : module.settings) {
            for (Setting<?> setting : group) {
                titles.add(setting.title.toLowerCase(Locale.ROOT));
            }
        }

        cached = titles.toArray(String[]::new);
        ORBITER_SETTING_TITLE_CACHE.put(module, cached);
        return cached;
    }

    // Exact equivalent of Meteor's levenshteinDistance(from, to, 1, 8, 8), but with O(n) memory.
    private static int orbiter$weightedDistance(String from, String to, int[][] workspace) {
        int fromLength = from.length();
        int toLength = to.length();

        if (fromLength == 0) return toLength;
        if (toLength == 0) return fromLength * 8;

        int required = toLength + 1;
        if (workspace[0].length < required) {
            workspace[0] = new int[required];
            workspace[1] = new int[required];
        }

        int[] previous = workspace[0];
        int[] current = workspace[1];

        for (int j = 0; j <= toLength; j++) previous[j] = j;

        for (int i = 1; i <= fromLength; i++) {
            current[0] = i * 8;
            char fromChar = from.charAt(i - 1);

            for (int j = 1; j <= toLength; j++) {
                int substitute = previous[j - 1] + (fromChar == to.charAt(j - 1) ? 0 : 8);
                int delete = previous[j] + 8;
                int insert = current[j - 1] + 1;
                current[j] = Math.min(Math.min(delete, insert), substitute);
            }

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[toLength];
    }

    @ModifyReturnValue(method = "loopCategories", at = @At("RETURN"))
    private static Iterable<Category> orbiter$filterHiddenCategories(Iterable<Category> original) {
        HiddenCategories hidden = orbiter$hiddenCategories();
        List<Category> filtered = new ArrayList<>();
        for (Category category : original) {
            if (!hidden.hides(category)) filtered.add(category);
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    @ModifyReturnValue(method = "searchTitles", at = @At("RETURN"))
    private static List<?> orbiter$filterSearchTitles(List<?> original) {
        HiddenCategories hidden = orbiter$hiddenCategories();
        if (!hidden.stupid && !hidden.wip) return original;

        List<Object> filtered = new ArrayList<>(original.size());
        for (Object entry : original) {
            Module module = (Module) ((Pair<?, ?>) entry).getFirst();
            if (!hidden.hides(module.category)) filtered.add(entry);
        }
        return filtered;
    }

    @Inject(method = "searchSettingTitles", at = @At("HEAD"), cancellable = true)
    private void orbiter$fastSearchSettingTitles(String text, CallbackInfoReturnable<Set<Module>> cir) {
        HiddenCategories hidden = orbiter$hiddenCategories();
        String query = text.toLowerCase(Locale.ROOT);
        int[][] workspace = { new int[64], new int[64] };

        List<Map.Entry<Module, Integer>> scores = new ArrayList<>();
        for (Module module : ((Modules) (Object) this).getAll()) {
            if (hidden.hides(module.category)) continue;

            int lowest = Integer.MAX_VALUE;
            for (String title : orbiter$settingTitles(module)) {
                int score = orbiter$weightedDistance(query, title, workspace);
                if (score < lowest) lowest = score;
                if (lowest == 0) break;
            }

            scores.add(Map.entry(module, lowest));
        }

        scores.sort(Comparator.comparingInt(Map.Entry::getValue));

        Set<Module> ordered = new LinkedHashSet<>(scores.size());
        for (Map.Entry<Module, Integer> score : scores) ordered.add(score.getKey());
        cir.setReturnValue(ordered);
    }
}
