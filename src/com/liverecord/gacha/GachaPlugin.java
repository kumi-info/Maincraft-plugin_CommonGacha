package com.liverecord.gacha;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 汎用ガチャプラグイン。
 *  - /gacha preset <id> : 指定プリセットを抽選。スロット風アニメ→ランク表記→当選内容の順で表示し commands を実行。
 *  - /gacha list / reload
 * レア度（ランク）は各エントリの「当選確率」から自動判定する（config の rarity-tiers）。
 * commands 内の %player% は実行者名に置換され、コンソール権限で実行される。
 */
public final class GachaPlugin extends JavaPlugin {

    /** レア度ランク定義（確率しきい値・⭐数・色）。 */
    private static final class Tier {
        final String rank;
        final double maxChance; // この確率(%)以下ならこのランク
        final int stars;
        final NamedTextColor color;

        Tier(String rank, double maxChance, int stars, NamedTextColor color) {
            this.rank = rank;
            this.maxChance = maxChance;
            this.stars = stars;
            this.color = color;
        }
    }

    private volatile boolean rolling = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getResource("README.md") != null) {
            saveResource("README.md", true); // plugins/Gacha/README.md を毎回最新化
        }
        getLogger().info("Gacha 有効化。/gacha が利用可能。");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("gacha")) {
            return false;
        }
        if (!sender.hasPermission("gacha.use")) {
            sender.sendMessage("§cこのコマンドを使う権限がありません。");
            return true;
        }
        String sub = (args.length == 0) ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "preset":
            case "roll":
                if (args.length < 2) {
                    sender.sendMessage("§7使い方: /gacha preset <id>");
                    return true;
                }
                rollGacha(sender, args[1]);
                return true;
            case "list":
                listPresets(sender);
                return true;
            case "reload":
                reloadConfig();
                sender.sendMessage("§aconfig.yml を再読み込みしました。");
                return true;
            case "test":
            case "demo": {
                if (args.length < 2) {
                    sender.sendMessage("§7使い方: /gacha test <1-5>  （星ランクの演出だけを確認）");
                    return true;
                }
                Integer st = parseIntOrNull(args[1]);
                if (st == null || st < 1 || st > 5) {
                    sender.sendMessage("§c星は 1〜5 で指定してください（5=HR, 4=SSR, 3=SR, 2=R, 1=N 相当）。");
                    return true;
                }
                runPreview(sender, st);
                return true;
            }
            case "force": {
                if (args.length < 3) {
                    sender.sendMessage("§7使い方: /gacha force <preset> <エントリキー>  （特定エントリを確定で当てる）");
                    return true;
                }
                rollGacha(sender, args[1], args[2]);
                return true;
            }
            default:
                sender.sendMessage("§7使い方: /gacha preset <id> | list | reload | test <1-5> | force <preset> <key>");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("gacha")) {
            return null;
        }
        if (args.length == 1) {
            return filterPrefix(Arrays.asList("preset", "list", "reload", "test", "force"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("preset")
                || args[0].equalsIgnoreCase("roll") || args[0].equalsIgnoreCase("force"))) {
            ConfigurationSection presets = getConfig().getConfigurationSection("presets");
            List<String> ids = (presets == null)
                    ? new ArrayList<>()
                    : new ArrayList<>(presets.getKeys(false));
            return filterPrefix(ids, args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("test") || args[0].equalsIgnoreCase("demo"))) {
            return filterPrefix(Arrays.asList("1", "2", "3", "4", "5"), args[1]);
        }
        // /gacha force <preset> <エントリキー> の第3引数: エントリ一覧
        if (args.length == 3 && args[0].equalsIgnoreCase("force")) {
            ConfigurationSection entries = getConfig().getConfigurationSection("presets." + args[1] + ".entries");
            List<String> keys = (entries == null)
                    ? new ArrayList<>()
                    : new ArrayList<>(entries.getKeys(false));
            return filterPrefix(keys, args[2]);
        }
        return new ArrayList<>();
    }

    /** 候補のうち入力プレフィックスに前方一致するものだけを返す。 */
    private List<String> filterPrefix(List<String> options, String prefix) {
        String p = (prefix == null) ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    private void listPresets(CommandSender sender) {
        ConfigurationSection presets = getConfig().getConfigurationSection("presets");
        if (presets == null || presets.getKeys(false).isEmpty()) {
            sender.sendMessage("§7プリセットが未定義です（config.yml の presets を確認）。");
            return;
        }
        sender.sendMessage("§eガチャプリセット一覧:");
        for (String key : presets.getKeys(false)) {
            String name = presets.getString(key + ".name", "");
            int count = 0;
            ConfigurationSection entries = presets.getConfigurationSection(key + ".entries");
            if (entries != null) {
                count = entries.getKeys(false).size();
            }
            sender.sendMessage("§7- §f" + key
                    + (name.isEmpty() ? "" : " §7(" + name + ")")
                    + " §8[" + count + " 件]");
        }
    }

    /** プリセットを重み付き抽選し、スロット→ランク表記→当選内容の順に演出して commands を実行。 */
    private void rollGacha(CommandSender sender, String presetId) {
        rollGacha(sender, presetId, null);
    }

    /**
     * プリセットを抽選して演出。forcedKey が非nullの場合は乱数を使わずそのエントリを確定で当選させる
     * （挙動確認用の /gacha force）。
     */
    private void rollGacha(CommandSender sender, String presetId, String forcedKey) {
        if (rolling) {
            sender.sendMessage("§eガチャ実行中です。少し待ってください。");
            return;
        }
        ConfigurationSection preset = getConfig().getConfigurationSection("presets." + presetId);
        if (preset == null) {
            sender.sendMessage("§cプリセット '" + presetId + "' が見つかりません。/gacha list で確認。");
            return;
        }
        ConfigurationSection entries = preset.getConfigurationSection("entries");
        if (entries == null || entries.getKeys(false).isEmpty()) {
            sender.sendMessage("§cプリセット '" + presetId + "' にエントリがありません。");
            return;
        }

        List<String> keys = new ArrayList<>(entries.getKeys(false));
        double total = 0.0;
        for (String key : keys) {
            total += Math.max(0.0, entries.getDouble(key + ".weight", 1.0));
        }
        if (total <= 0.0) {
            sender.sendMessage("§cプリセット '" + presetId + "' の weight 合計が 0 です。");
            return;
        }

        String winnerKey;
        if (forcedKey != null) {
            if (!keys.contains(forcedKey)) {
                sender.sendMessage("§cエントリ '" + forcedKey + "' は preset '" + presetId
                        + "' にありません。候補: §f" + String.join(", ", keys));
                return;
            }
            winnerKey = forcedKey;
            sender.sendMessage("§7[force] preset '" + presetId + "' のエントリ '" + forcedKey + "' を確定で当選させます。");
        } else {
            double r = ThreadLocalRandom.current().nextDouble(total);
            winnerKey = keys.get(keys.size() - 1);
            for (String key : keys) {
                double w = Math.max(0.0, entries.getDouble(key + ".weight", 1.0));
                if (r < w) {
                    winnerKey = key;
                    break;
                }
                r -= w;
            }
        }

        String playerName = (sender instanceof Player) ? sender.getName() : "CONSOLE";
        final List<Tier> tiers = loadTiers();
        final int rollSeconds = getConfig().getInt("animation.roll-seconds", 3);
        final double finalTotal = total;
        final String finalWinnerKey = winnerKey;
        final String finalPlayerName = playerName;

        getLogger().info("[gacha] 抽選開始 preset=" + presetId + " 実行者=" + playerName
                + " 候補=" + keys.size() + "件 当選=" + winnerKey);

        rolling = true;

        // 抽選開始の合図（ガチャ名を数秒間タイトル表示してから回転を始める）。
        String presetName = preset.getString("name", presetId);
        int nameTitleSecs = getConfig().getInt("animation.name-title-seconds", 2);
        // タイトル色は config で変更可能（preset 個別 > animation 全体 > 既定 gold）。
        NamedTextColor mainColor = colorByName(preset.getString("name-color",
                getConfig().getString("animation.name-title-color", "gold")));
        if (nameTitleSecs > 0) {
            showInstantTitle(
                    Component.text(presetName).color(mainColor).decorate(TextDecoration.BOLD),
                    Component.empty(),
                    nameTitleSecs * 1000L);
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
            }
        }

        // ガチャ名表示が終わってからスロット回転（または即決）を開始する。
        Runnable start = () -> {
            if (rollSeconds <= 0) {
                revealRank(presetId, entries, finalTotal, tiers, finalWinnerKey, finalPlayerName);
                return;
            }
            long now = System.currentTimeMillis();
            spin(presetId, entries, keys, finalTotal, tiers, finalWinnerKey, finalPlayerName,
                    now, now + rollSeconds * 1000L);
        };
        if (nameTitleSecs > 0) {
            Bukkit.getScheduler().runTaskLater(this, start, nameTitleSecs * 20L);
        } else {
            start.run();
        }
    }

    /** スロット回転中。終端に近づくほど間隔を遅くする。 */
    private void spin(String presetId, ConfigurationSection entries, List<String> keys, double total,
                      List<Tier> tiers, String winnerKey, String playerName, long start, long end) {
        long now = System.currentTimeMillis();
        if (now >= end) {
            revealRank(presetId, entries, total, tiers, winnerKey, playerName);
            return;
        }

        String key = keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
        ConfigurationSection e = entries.getConfigurationSection(key);
        if (e != null) {
            double chance = Math.max(0.0, e.getDouble("weight", 1.0)) / total * 100.0;
            Tier tier = tierFor(chance, tiers);
            String name = e.getString("name", key);
            showInstantTitle(
                    Component.text(name).color(tier.color).decorate(TextDecoration.BOLD),
                    Component.text(stars(tier.stars)).color(NamedTextColor.YELLOW), 400);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.2f);
        }

        double fraction = (double) (now - start) / (double) (end - start);
        long delay = 2L + Math.round(6.0 * fraction);
        Bukkit.getScheduler().runTaskLater(this,
                () -> spin(presetId, entries, keys, total, tiers, winnerKey, playerName, start, end), delay);
    }

    /** 第1段階: ランク（SSR等）を大きく表示してから、第2段階で当選内容を表示する。 */
    private void revealRank(String presetId, ConfigurationSection entries, double total,
                            List<Tier> tiers, String winnerKey, String playerName) {
        ConfigurationSection won = entries.getConfigurationSection(winnerKey);
        if (won == null) {
            rolling = false;
            return;
        }
        double chance = Math.max(0.0, won.getDouble("weight", 1.0)) / total * 100.0;
        Tier tier = tierFor(chance, tiers);
        String starStr = stars(tier.stars);

        // 第1段階: ランク表記
        showInstantTitle(
                Component.text(tier.rank).color(tier.color).decorate(TextDecoration.BOLD),
                Component.text(starStr).color(NamedTextColor.YELLOW), 1500);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, pitchByStars(tier.stars));
        }
        getLogger().info("[gacha] ランク確定 preset=" + presetId + " rank=" + tier.rank
                + " chance=" + String.format(Locale.ROOT, "%.2f", chance) + "%");

        // 第2段階: 当選内容（約1.2秒後）
        Bukkit.getScheduler().runTaskLater(this,
                () -> revealWinner(presetId, won, tier, winnerKey, playerName), 25L);
    }

    /** 第2段階: 当選内容の表示＋効果音＋アナウンス＋コマンド実行。 */
    private void revealWinner(String presetId, ConfigurationSection won, Tier tier,
                              String winnerKey, String playerName) {
        int stars = tier.stars;
        String displayName = won.getString("name", winnerKey);
        String starStr = stars(stars);

        Component main = Component.text(displayName).color(tier.color).decorate(TextDecoration.BOLD);
        Component sub = Component.text("[" + tier.rank + "] " + starStr).color(NamedTextColor.YELLOW);
        Title.Times t = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(3000), Duration.ofMillis(600));
        Title title = Title.title(main, sub, t);
        Sound winSound = winSound(stars);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), winSound, 1.0f, 1.0f);
        }
        playRarityEffect(stars);

        getLogger().info("[gacha] 当選確定 preset=" + presetId + " 実行者=" + playerName
                + " entry=" + winnerKey + " name=" + displayName + " rank=" + tier.rank + " stars=" + stars);

        String announce = getConfig().getString("messages.win",
                "§6[ガチャ] §f%player% §6は §e[%rank%] %name% %stars% §6を引き当てた！");
        String presetName = getConfig().getString("presets." + presetId + ".name", presetId);
        Bukkit.broadcastMessage(announce
                .replace("%presets%", presetName)
                .replace("%preset%", presetName)
                .replace("%rank%", tier.rank)
                .replace("%name%", displayName)
                .replace("%player%", playerName)
                .replace("%stars%", starStr));

        for (String cmd : won.getStringList("commands")) {
            if (cmd == null || cmd.trim().isEmpty()) {
                continue;
            }
            String resolved = cmd.replace("%player%", playerName).trim();
            if (resolved.startsWith("/")) {
                resolved = resolved.substring(1);
            }
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
            getLogger().info("[gacha] 実行: /" + resolved + " -> " + (ok ? "成功" : "失敗/不明コマンド"));
        }
        rolling = false;
    }

    /**
     * 高レア度（⭐4以上＝SR/SSR想定）の当選時に、各プレイヤー周囲へ花火＋パーティクル＋効果音を出す。
     * ⭐5は花火を多め＋金色、⭐4は1発＋紫。⭐3以下は演出なし（通常の当選表示のみ）。
     */
    private void playRarityEffect(int stars) {
        if (stars < 4) {
            return;
        }
        int bursts = (stars >= 5) ? 3 : 1;
        Color main = (stars >= 5) ? Color.fromRGB(0xFFD700) : Color.fromRGB(0xC850FF);
        FireworkEffect.Type type = (stars >= 5) ? FireworkEffect.Type.STAR : FireworkEffect.Type.BALL_LARGE;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                    p.getLocation().add(0, 1, 0), 60, 0.6, 1.0, 0.6, 0.4);
            p.playSound(p.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);
            for (int i = 0; i < bursts; i++) {
                Firework fw = p.getWorld().spawn(p.getLocation(), Firework.class);
                FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder()
                        .withColor(main)
                        .withFade(Color.WHITE)
                        .with(type)
                        .trail(true)
                        .flicker(true)
                        .build());
                meta.setPower(1);
                fw.setFireworkMeta(meta);
            }
        }
    }

    /**
     * 挙動確認用: 実際の抽選をせず、指定の星ランク(1-5)の演出だけを再生する。
     * commands は実行せず、確率にも一切影響しない。
     */
    private void runPreview(CommandSender sender, int stars) {
        if (rolling) {
            sender.sendMessage("§eガチャ実行中です。少し待ってください。");
            return;
        }
        List<Tier> tiers = loadTiers();
        final Tier tier = tierByStars(stars, tiers);
        final String starStr = stars(tier.stars);
        rolling = true;
        sender.sendMessage("§7[test] ランク §e" + tier.rank + " §7(⭐" + tier.stars + ") の演出を再生します。");

        // 第1段階: ランク表記
        showInstantTitle(
                Component.text(tier.rank).color(tier.color).decorate(TextDecoration.BOLD),
                Component.text(starStr).color(NamedTextColor.YELLOW), 1500);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, pitchByStars(tier.stars));
        }

        // 第2段階: ダミー当選表示＋エフェクト
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Component main = Component.text("テスト演出").color(tier.color).decorate(TextDecoration.BOLD);
            Component sub = Component.text("[" + tier.rank + "] " + starStr).color(NamedTextColor.YELLOW);
            Title.Times t = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(3000), Duration.ofMillis(600));
            Title title = Title.title(main, sub, t);
            Sound ws = winSound(tier.stars);
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(title);
                p.playSound(p.getLocation(), ws, 1.0f, 1.0f);
            }
            playRarityEffect(tier.stars);
            rolling = false;
        }, 25L);
    }

    /** 指定の星数に最も近い Tier を返す（完全一致優先）。 */
    private Tier tierByStars(int stars, List<Tier> tiers) {
        Tier best = tiers.get(tiers.size() - 1);
        int bestDiff = Integer.MAX_VALUE;
        for (Tier t : tiers) {
            if (t.stars == stars) {
                return t;
            }
            int diff = Math.abs(t.stars - stars);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = t;
            }
        }
        return best;
    }

    private Integer parseIntOrNull(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void showInstantTitle(Component main, Component sub, long stayMs) {
        Title.Times t = Title.Times.times(Duration.ZERO, Duration.ofMillis(stayMs), Duration.ZERO);
        Title title = Title.title(main, sub, t);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
        }
    }

    // ===================== レア度（確率→ランク） =====================

    /** config の rarity-tiers を読み込み、maxChance 昇順（厳しい順）に並べる。 */
    private List<Tier> loadTiers() {
        List<Tier> tiers = new ArrayList<>();
        for (Map<?, ?> m : getConfig().getMapList("rarity-tiers")) {
            String rank = String.valueOf(m.get("rank"));
            double maxChance = toDouble(m.get("max-chance"), 100.0);
            int starN = (int) toDouble(m.get("stars"), 1.0);
            NamedTextColor color = colorByName(String.valueOf(m.get("color")));
            tiers.add(new Tier(rank, maxChance, starN, color));
        }
        if (tiers.isEmpty()) {
            tiers.add(new Tier("SSR", 1.0, 5, NamedTextColor.GOLD));
            tiers.add(new Tier("SR", 5.0, 4, NamedTextColor.LIGHT_PURPLE));
            tiers.add(new Tier("HR", 15.0, 3, NamedTextColor.AQUA));
            tiers.add(new Tier("R", 40.0, 2, NamedTextColor.GREEN));
            tiers.add(new Tier("N", 100.0, 1, NamedTextColor.GRAY));
        }
        tiers.sort(Comparator.comparingDouble(a -> a.maxChance));
        return tiers;
    }

    /** 当選確率(%)が小さいほど高ランク。max-chance 以下になる最初のランクを返す。 */
    private Tier tierFor(double chance, List<Tier> tiers) {
        for (Tier t : tiers) {
            if (chance <= t.maxChance) {
                return t;
            }
        }
        return tiers.get(tiers.size() - 1);
    }

    private double toDouble(Object o, double def) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private NamedTextColor colorByName(String name) {
        if (name == null) {
            return NamedTextColor.WHITE;
        }
        NamedTextColor c = NamedTextColor.NAMES.value(name.toLowerCase(Locale.ROOT));
        return (c == null) ? NamedTextColor.WHITE : c;
    }

    private String stars(int n) {
        int count = Math.max(0, Math.min(n, 10));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append("⭐");
        }
        return sb.toString();
    }

    private float pitchByStars(int stars) {
        return Math.max(0.5f, Math.min(2.0f, 0.6f + stars * 0.25f));
    }

    private Sound winSound(int stars) {
        if (stars >= 5) {
            return Sound.UI_TOAST_CHALLENGE_COMPLETE;
        }
        if (stars >= 3) {
            return Sound.ENTITY_PLAYER_LEVELUP;
        }
        return Sound.BLOCK_NOTE_BLOCK_PLING;
    }
}
