package com.liverecord.gacha;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.HSVLike;
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

/**
 * 汎用ガチャプラグイン。
 *
 * <p>プリセットを重み付き抽選し、当選結果に応じたコンソールコマンドを実行する。
 * スロット風アニメ、レア度ランク表示、花火エフェクト等の演出機能を備える。</p>
 *
 * <ul>
 *   <li>{@code /gacha preset <id>} - 指定プリセットを抽選</li>
 *   <li>{@code /gacha list} - プリセット一覧</li>
 *   <li>{@code /gacha reload} - config.yml 再読み込み</li>
 *   <li>{@code /gacha test <1-5>} - 演出プレビュー</li>
 *   <li>{@code /gacha force <preset> <key>} - 確定当選</li>
 * </ul>
 *
 * <p>レア度（ランク）は各エントリの当選確率から自動判定する（config の rarity-tiers）。</p>
 *
 * <p>エントリに {@code chain: '<presetId>'} を書くと、当選後に指定プリセットを
 * 自動で連続抽選する（倍々ガチャ等の多段演出用）。</p>
 *
 * <p>レア度色に {@code RAINBOW} を指定すると、タイトルが1文字ずつ色相をずらした
 * 虹色グラデーションで、色が流れるアニメーション表示になる（UR 用）。</p>
 *
 * @version 1.8.0
 * @author LiveRecord
 */
public final class GachaPlugin extends JavaPlugin {

    // ── 演出タイミング定数 ──

    /** スロット回転中の各エントリ表示時間（ミリ秒）。 */
    private static final long SLOT_DISPLAY_MS = 400;

    /** ランク確定時の表示時間（ミリ秒）。 */
    private static final long RANK_DISPLAY_MS = 1500;

    /** 当選タイトルの fadeIn（ミリ秒）。 */
    private static final long WIN_FADE_IN_MS = 100;

    /** 当選タイトルの stay（ミリ秒）。 */
    private static final long WIN_STAY_MS = 3000;

    /** 当選タイトルの fadeOut（ミリ秒）。 */
    private static final long WIN_FADE_OUT_MS = 600;

    /** ランク表示から当選表示への遅延（tick）。 */
    private static final long RANK_TO_WIN_DELAY_TICKS = 25L;

    /** スロット回転の最小ディレイ（tick）。 */
    private static final long SPIN_MIN_DELAY_TICKS = 2L;

    /** スロット回転のディレイ増分最大値（tick）。 */
    private static final double SPIN_DELAY_RANGE_TICKS = 6.0;

    // ── 花火・パーティクル定数 ──

    /** 高レア当選時のパーティクル数。 */
    private static final int RARITY_PARTICLE_COUNT = 60;

    /** パーティクルのXオフセット。 */
    private static final double PARTICLE_OFFSET_X = 0.6;

    /** パーティクルのYオフセット。 */
    private static final double PARTICLE_OFFSET_Y = 1.0;

    /** パーティクルのZオフセット。 */
    private static final double PARTICLE_OFFSET_Z = 0.6;

    /** パーティクルの速度。 */
    private static final double PARTICLE_SPEED = 0.4;

    /** 星6（UR）当選時の花火発数。 */
    private static final int FIREWORK_BURST_STAR6 = 5;

    /** 星5当選時の花火発数。 */
    private static final int FIREWORK_BURST_STAR5 = 3;

    /** 星4当選時の花火発数。 */
    private static final int FIREWORK_BURST_STAR4 = 1;

    /** 花火エフェクト発動の最低星数。 */
    private static final int FIREWORK_MIN_STARS = 4;

    /** 星5花火の色（金色）。 */
    private static final Color FIREWORK_COLOR_GOLD = Color.fromRGB(0xFFD700);

    /** 星4花火の色（紫）。 */
    private static final Color FIREWORK_COLOR_PURPLE = Color.fromRGB(0xC850FF);

    /** 星6（UR）花火の色（虹7色）。 */
    private static final Color[] FIREWORK_COLORS_RAINBOW = {
            Color.fromRGB(0xFF3030),  // 赤
            Color.fromRGB(0xFF8C00),  // 橙
            Color.fromRGB(0xFFE930),  // 黄
            Color.fromRGB(0x30E030),  // 緑
            Color.fromRGB(0x30D0FF),  // 水
            Color.fromRGB(0x4060FF),  // 青
            Color.fromRGB(0xC850FF),  // 紫
    };

    // ── 虹色（RAINBOW）演出定数 ──

    /** 虹色アニメの1フレームあたりの tick 数。 */
    private static final long RAINBOW_FRAME_TICKS = 2L;

    /** 虹色アニメの1フレームの表示時間（ミリ秒）。フレーム間隔より長くして途切れを防ぐ。 */
    private static final long RAINBOW_FRAME_STAY_MS = 500L;

    /** 虹色グラデの1文字あたりの色相ステップ（0〜1）。 */
    private static final float RAINBOW_HUE_STEP = 0.09f;

    /** 虹色アニメの1フレームあたりの色相シフト（0〜1）。色が右→左へ流れて見える。 */
    private static final float RAINBOW_HUE_SHIFT_PER_FRAME = 0.05f;

    /** 虹色の彩度（0〜1）。1.0 より少し落とすと Minecraft のタイトルで読みやすい。 */
    private static final float RAINBOW_SATURATION = 0.85f;

    /** 虹色の明度（0〜1）。 */
    private static final float RAINBOW_VALUE = 1.0f;

    /** チャット（レガシー§コード）用の虹色コード列。%rank% の虹色置換に使う。 */
    private static final String[] RAINBOW_LEGACY_CODES = {
            "§c", "§6", "§e", "§a", "§b", "§9", "§d",
    };

    // ── 音量・ピッチ定数 ──

    /** ピッチ計算のベース値。 */
    private static final float PITCH_BASE = 0.6f;

    /** ピッチ計算の星あたり増分。 */
    private static final float PITCH_PER_STAR = 0.25f;

    /** ピッチの最小値。 */
    private static final float PITCH_MIN = 0.5f;

    /** ピッチの最大値。 */
    private static final float PITCH_MAX = 2.0f;

    /** 星表示の上限数。 */
    private static final int MAX_STAR_DISPLAY = 10;

    /** パーティクル表示のY軸オフセット。 */
    private static final double PARTICLE_Y_OFFSET = 1.0;

    // ── サブコマンド名 ──

    private static final String SUB_PRESET = "preset";
    private static final String SUB_ROLL = "roll";
    private static final String SUB_LIST = "list";
    private static final String SUB_RELOAD = "reload";
    private static final String SUB_TEST = "test";
    private static final String SUB_DEMO = "demo";
    private static final String SUB_FORCE = "force";

    /** 星数の最小値。 */
    private static final int STARS_MIN = 1;

    /** 星数の最大値。 */
    private static final int STARS_MAX = 6;

    /** UR（最高レア）判定しきい値（星6以上）。花火が虹色になる。 */
    private static final int STARS_UR = 6;

    /** 高レアサウンド判定しきい値（星5以上）。 */
    private static final int SOUND_THRESHOLD_HIGH = 5;

    /** 中レアサウンド判定しきい値（星3以上）。 */
    private static final int SOUND_THRESHOLD_MID = 3;

    /** Tick/秒変換係数。 */
    private static final long TICKS_PER_SECOND = 20L;

    /** ms/秒変換係数。 */
    private static final long MS_PER_SECOND = 1000L;

    /** ms/tick変換係数。 */
    private static final long MS_PER_TICK = MS_PER_SECOND / TICKS_PER_SECOND;

    // ── レア度ランクデータクラス ──

    /**
     * レア度ランク定義。
     *
     * <p>確率しきい値（maxChance）以下の当選確率を持つエントリに、
     * このランクが割り当てられる。</p>
     */
    private static final class Tier {
        /** ランク表示名（"SSR"等）。 */
        final String rank;

        /** この確率(%)以下ならこのランク。 */
        final double maxChance;

        /** 表示する星の数。 */
        final int stars;

        /** タイトル表示色（rainbow=true の場合は未使用）。 */
        final NamedTextColor color;

        /** 虹色（RAINBOW）表示かどうか。true なら虹グラデ＋アニメで表示する。 */
        final boolean rainbow;

        Tier(final String rank, final double maxChance,
             final int stars, final NamedTextColor color,
             final boolean rainbow) {
            this.rank = rank;
            this.maxChance = maxChance;
            this.stars = stars;
            this.color = color;
            this.rainbow = rainbow;
        }
    }

    /** 連鎖（chain）の最大段数。config ミスによる無限ループ防止。 */
    private static final int MAX_CHAIN_DEPTH = 20;

    /** 連鎖抽選開始までの既定ディレイ（秒）。当選タイトルを読み切れる長さ。 */
    private static final int DEFAULT_CHAIN_DELAY_SECONDS = 3;

    /** ガチャ演出中フラグ。true の間は新たなガチャ実行をブロックする。 */
    private volatile boolean rolling = false;

    /** 現在の連鎖段数。コマンドからの抽選開始で 0 に戻る。 */
    private int chainDepth = 0;

    /**
     * プラグイン有効化時の初期化処理。
     *
     * <p>config.yml の自動生成と README.md のプラグインフォルダへの展開を行う。</p>
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getResource("README.md") != null) {
            // plugins/CommonGacha/README.md を毎回最新化して利用者が参照できるようにする
            saveResource("README.md", true);
        }
        removeNamespacedAliases();
        getLogger().info("Gacha 有効化。/gacha が利用可能。");
    }

    /** タブ補完に出る「プラグイン名:コマンド名」形式の名前空間エイリアスをコマンドマップから削除する。 */
    @SuppressWarnings("unchecked")
    private void removeNamespacedAliases() {
        try {
            Object server = getServer();
            java.lang.reflect.Method getMap = server.getClass().getMethod("getCommandMap");
            getMap.setAccessible(true);
            Object commandMap = getMap.invoke(server);

            java.lang.reflect.Field f = null;
            for (Class<?> c = commandMap.getClass(); c != null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField("knownCommands"); break; } catch (NoSuchFieldException ignored) {}
            }
            if (f == null) {
                getLogger().warning("[alias] knownCommands フィールドが見つかりません: " + commandMap.getClass().getName());
                return;
            }
            f.setAccessible(true);
            java.util.Map<String, ?> known = (java.util.Map<String, ?>) f.get(commandMap);
            String prefix = getName().toLowerCase(java.util.Locale.ROOT) + ":";
            boolean removed = known.keySet().removeIf(k -> k.startsWith(prefix));
            getLogger().info("[alias] " + prefix + "* の名前空間エイリアス削除: " + (removed ? "成功" : "キーなし"));

            try {
                java.lang.reflect.Method sync = server.getClass().getMethod("syncCommands");
                sync.setAccessible(true);
                sync.invoke(server);
            } catch (Exception e) {
                getLogger().warning("[alias] syncCommands 失敗: " + e.getMessage());
            }
        } catch (Exception e) {
            getLogger().warning("[alias] 名前空間エイリアス削除失敗: " + e);
        }
    }

    /**
     * コマンド実行のエントリポイント。
     *
     * @param sender  コマンド実行者
     * @param command コマンド定義
     * @param label   使用されたエイリアス
     * @param args    引数配列
     * @return コマンドを処理した場合 true
     */
    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!command.getName().equalsIgnoreCase("gacha")) {
            return false;
        }
        if (!sender.hasPermission("gacha.use")) {
            sender.sendMessage("§cこのコマンドを使う権限がありません。");
            return true;
        }

        final String sub = (args.length == 0)
                ? "help"
                : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case SUB_PRESET:
            case SUB_ROLL:
                return handlePreset(sender, args);
            case SUB_LIST:
                listPresets(sender);
                return true;
            case SUB_RELOAD:
                handleReload(sender);
                return true;
            case SUB_TEST:
            case SUB_DEMO:
                return handleTest(sender, args);
            case SUB_FORCE:
                return handleForce(sender, args);
            default:
                sendUsage(sender);
                return true;
        }
    }

    /**
     * タブ補完の候補を返す。
     *
     * @param sender  コマンド実行者
     * @param command コマンド定義
     * @param alias   使用されたエイリアス
     * @param args    入力中の引数配列
     * @return 補完候補リスト
     */
    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (!command.getName().equalsIgnoreCase("gacha")) {
            return null;
        }
        if (args.length == 1) {
            return filterPrefix(
                    Arrays.asList(SUB_PRESET, SUB_LIST, SUB_RELOAD, SUB_TEST, SUB_FORCE),
                    args[0]);
        }
        if (args.length == 2 && isPresetSubCommand(args[0])) {
            return filterPrefix(getPresetIds(), args[1]);
        }
        if (args.length == 2 && isTestSubCommand(args[0])) {
            return filterPrefix(
                    Arrays.asList("1", "2", "3", "4", "5", "6"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase(SUB_FORCE)) {
            return filterPrefix(getEntryKeys(args[1]), args[2]);
        }
        return new ArrayList<>();
    }

    // ── サブコマンドハンドラ ──

    /**
     * preset/roll サブコマンドを処理する。
     *
     * @param sender コマンド実行者
     * @param args   引数配列
     * @return 常に true
     */
    private boolean handlePreset(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7使い方: /gacha preset <id>");
            return true;
        }
        rollGacha(sender, args[1]);
        return true;
    }

    /**
     * reload サブコマンドを処理する。
     *
     * @param sender コマンド実行者
     */
    private void handleReload(final CommandSender sender) {
        try {
            reloadConfig();
            sender.sendMessage("§aconfig.yml を再読み込みしました。");
        } catch (final Exception e) {
            sender.sendMessage("§cconfig.yml の再読み込みに失敗しました。");
            getLogger().warning("config.yml の再読み込みに失敗: " + e.getMessage());
        }
    }

    /**
     * test/demo サブコマンドを処理する。
     *
     * @param sender コマンド実行者
     * @param args   引数配列
     * @return 常に true
     */
    private boolean handleTest(final CommandSender sender, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage(
                    "§7使い方: /gacha test <1-6>  （星ランクの演出だけを確認）");
            return true;
        }
        final Integer starCount = parseIntOrNull(args[1]);
        if (starCount == null || starCount < STARS_MIN || starCount > STARS_MAX) {
            sender.sendMessage(
                    "§c星は 1〜6 で指定してください"
                    + "（6=UR, 5=HR, 4=SSR, 3=SR, 2=R, 1=N 相当）。");
            return true;
        }
        runPreview(sender, starCount);
        return true;
    }

    /**
     * force サブコマンドを処理する。
     *
     * @param sender コマンド実行者
     * @param args   引数配列
     * @return 常に true
     */
    private boolean handleForce(final CommandSender sender, final String[] args) {
        if (args.length < 3) {
            sender.sendMessage(
                    "§7使い方: /gacha force <preset> <エントリキー>"
                    + "  （特定エントリを確定で当てる）");
            return true;
        }
        rollGacha(sender, args[1], args[2]);
        return true;
    }

    /**
     * ヘルプ（使い方）メッセージを送信する。
     *
     * @param sender メッセージ送信先
     */
    private void sendUsage(final CommandSender sender) {
        sender.sendMessage(
                "§7使い方: /gacha preset <id> | list | reload | test <1-6>"
                + " | force <preset> <key>");
    }

    // ── タブ補完ヘルパー ──

    /**
     * プリセット関連のサブコマンドかどうかを判定する。
     *
     * @param sub サブコマンド文字列
     * @return preset/roll/force のいずれかなら true
     */
    private boolean isPresetSubCommand(final String sub) {
        return sub.equalsIgnoreCase(SUB_PRESET)
                || sub.equalsIgnoreCase(SUB_ROLL)
                || sub.equalsIgnoreCase(SUB_FORCE);
    }

    /**
     * テスト関連のサブコマンドかどうかを判定する。
     *
     * @param sub サブコマンド文字列
     * @return test/demo のいずれかなら true
     */
    private boolean isTestSubCommand(final String sub) {
        return sub.equalsIgnoreCase(SUB_TEST)
                || sub.equalsIgnoreCase(SUB_DEMO);
    }

    /**
     * 全プリセットIDのリストを取得する。
     *
     * @return プリセットIDリスト（未定義時は空リスト）
     */
    private List<String> getPresetIds() {
        final ConfigurationSection presets =
                getConfig().getConfigurationSection("presets");
        if (presets == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(presets.getKeys(false));
    }

    /**
     * 指定プリセットのエントリキー一覧を取得する。
     *
     * @param presetId プリセットID
     * @return エントリキーリスト（未定義時は空リスト）
     */
    private List<String> getEntryKeys(final String presetId) {
        final ConfigurationSection entries =
                getConfig().getConfigurationSection(
                        "presets." + presetId + ".entries");
        if (entries == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(entries.getKeys(false));
    }

    /**
     * 候補のうち入力プレフィックスに前方一致するものだけを返す。
     *
     * @param options 候補リスト
     * @param prefix  入力プレフィックス（null 可）
     * @return フィルタ済みリスト
     */
    private List<String> filterPrefix(final List<String> options,
                                      final String prefix) {
        final String p = (prefix == null)
                ? ""
                : prefix.toLowerCase(Locale.ROOT);
        final List<String> out = new ArrayList<>();
        for (final String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    // ── プリセット一覧 ──

    /**
     * 全プリセットの一覧をコマンド実行者に送信する。
     *
     * @param sender メッセージ送信先
     */
    private void listPresets(final CommandSender sender) {
        final ConfigurationSection presets =
                getConfig().getConfigurationSection("presets");
        if (presets == null || presets.getKeys(false).isEmpty()) {
            sender.sendMessage(
                    "§7プリセットが未定義です"
                    + "（config.yml の presets を確認）。");
            return;
        }
        sender.sendMessage("§eガチャプリセット一覧:");
        for (final String key : presets.getKeys(false)) {
            final String name = presets.getString(key + ".name", "");
            final ConfigurationSection entries =
                    presets.getConfigurationSection(key + ".entries");
            final int count = (entries != null)
                    ? entries.getKeys(false).size()
                    : 0;
            sender.sendMessage("§7- §f" + key
                    + (name.isEmpty() ? "" : " §7(" + name + ")")
                    + " §8[" + count + " 件]");
        }
    }

    // ── 抽選ロジック ──

    /**
     * プリセットを重み付き抽選し、演出してコマンドを実行する。
     *
     * @param sender   コマンド実行者
     * @param presetId プリセットID
     */
    private void rollGacha(final CommandSender sender, final String presetId) {
        rollGacha(sender, presetId, null, null);
    }

    /**
     * プリセットを抽選して演出する。
     *
     * <p>{@code forcedKey} が非 null の場合は乱数を使わず、
     * そのエントリを確定で当選させる（{@code /gacha force} 用）。</p>
     *
     * @param sender    コマンド実行者
     * @param presetId  プリセットID
     * @param forcedKey 確定当選させるエントリキー（null で通常抽選）
     */
    private void rollGacha(final CommandSender sender,
                           final String presetId,
                           final String forcedKey) {
        rollGacha(sender, presetId, forcedKey, null);
    }

    /**
     * プリセットを抽選して演出する（実行者名の引き継ぎ対応）。
     *
     * <p>{@code chainPlayerName} が非 null の場合は連鎖（chain）による内部呼び出しで、
     * 元の実行者名を %player% 置換に引き継ぐ。null の場合はコマンドからの
     * 新規抽選として連鎖段数をリセットする。</p>
     *
     * @param sender          コマンド実行者
     * @param presetId        プリセットID
     * @param forcedKey       確定当選させるエントリキー（null で通常抽選）
     * @param chainPlayerName 連鎖時に引き継ぐ実行者名（null で sender から取得）
     */
    private void rollGacha(final CommandSender sender,
                           final String presetId,
                           final String forcedKey,
                           final String chainPlayerName) {
        if (rolling) {
            sender.sendMessage(
                    "§eガチャ実行中です。"
                    + "少し待ってください。");
            return;
        }

        final ConfigurationSection preset =
                getConfig().getConfigurationSection("presets." + presetId);
        if (preset == null) {
            sender.sendMessage("§cプリセット '" + presetId
                    + "' が見つかりません。"
                    + "/gacha list で確認。");
            return;
        }

        final ConfigurationSection entries =
                preset.getConfigurationSection("entries");
        if (entries == null || entries.getKeys(false).isEmpty()) {
            sender.sendMessage("§cプリセット '" + presetId
                    + "' にエントリがありません。");
            return;
        }

        final List<String> keys = new ArrayList<>(entries.getKeys(false));
        final double total = calculateTotalWeight(entries, keys);
        if (total <= 0.0) {
            sender.sendMessage("§cプリセット '" + presetId
                    + "' の weight 合計が 0 です。");
            return;
        }

        final String winnerKey = determineWinner(
                sender, entries, keys, total, presetId, forcedKey);
        if (winnerKey == null) {
            // forcedKey が不正だった場合（エラーメッセージは determineWinner 内で送信済み）
            return;
        }

        if (chainPlayerName == null) {
            // コマンドからの新規抽選 → 連鎖段数をリセット
            chainDepth = 0;
        }
        final String playerName = (chainPlayerName != null)
                ? chainPlayerName
                : (sender instanceof Player)
                        ? sender.getName()
                        : "CONSOLE";
        final List<Tier> tiers = loadTiers();
        final int rollSeconds =
                getConfig().getInt("animation.roll-seconds", 3);

        getLogger().info("[gacha] 抽選開始 preset=" + presetId
                + " 実行者=" + playerName
                + " 候補=" + keys.size() + "件"
                + " 当選=" + winnerKey);

        rolling = true;
        startGachaAnimation(preset, presetId, entries, keys,
                total, tiers, winnerKey, playerName, rollSeconds);
    }

    /**
     * 全エントリの weight 合計を計算する。
     *
     * @param entries エントリセクション
     * @param keys    エントリキーリスト
     * @return weight 合計値（各 weight は 0 以上にクランプ）
     */
    private double calculateTotalWeight(final ConfigurationSection entries,
                                        final List<String> keys) {
        double total = 0.0;
        for (final String key : keys) {
            total += Math.max(0.0,
                    entries.getDouble(key + ".weight", 1.0));
        }
        return total;
    }

    /**
     * 当選エントリを決定する。
     *
     * <p>forcedKey が非 null の場合は指定エントリを確定で返す。
     * null の場合は重み付き抽選で選出する。</p>
     *
     * @param sender    エラーメッセージ送信先
     * @param entries   エントリセクション
     * @param keys      エントリキーリスト
     * @param total     weight 合計
     * @param presetId  プリセットID（エラーメッセージ用）
     * @param forcedKey 確定キー（null で通常抽選）
     * @return 当選キー。forcedKey が不正な場合は null
     */
    private String determineWinner(final CommandSender sender,
                                   final ConfigurationSection entries,
                                   final List<String> keys,
                                   final double total,
                                   final String presetId,
                                   final String forcedKey) {
        if (forcedKey != null) {
            if (!keys.contains(forcedKey)) {
                sender.sendMessage("§cエントリ '" + forcedKey
                        + "' は preset '" + presetId
                        + "' にありません。候補: §f"
                        + String.join(", ", keys));
                return null;
            }
            sender.sendMessage("§7[force] preset '" + presetId
                    + "' のエントリ '" + forcedKey
                    + "' を確定で当選させます。");
            return forcedKey;
        }

        // 重み付き抽選: 乱数 r を weight で累積減算し、r < w となったエントリが当選
        double r = ThreadLocalRandom.current().nextDouble(total);
        String winner = keys.get(keys.size() - 1);
        for (final String key : keys) {
            final double w = Math.max(0.0,
                    entries.getDouble(key + ".weight", 1.0));
            if (r < w) {
                winner = key;
                break;
            }
            r -= w;
        }
        return winner;
    }

    /**
     * ガチャ演出（ガチャ名表示 → スロット回転）を開始する。
     *
     * @param preset      プリセットセクション
     * @param presetId    プリセットID
     * @param entries     エントリセクション
     * @param keys        エントリキーリスト
     * @param total       weight 合計
     * @param tiers       レア度リスト
     * @param winnerKey   当選キー
     * @param playerName  実行者名
     * @param rollSeconds スロット回転秒数
     */
    private void startGachaAnimation(final ConfigurationSection preset,
                                     final String presetId,
                                     final ConfigurationSection entries,
                                     final List<String> keys,
                                     final double total,
                                     final List<Tier> tiers,
                                     final String winnerKey,
                                     final String playerName,
                                     final int rollSeconds) {
        final String presetName = preset.getString("name", presetId);
        final int nameTitleSecs =
                getConfig().getInt("animation.name-title-seconds", 2);

        // タイトル色: preset 個別 > animation 全体 > 既定 gold
        final NamedTextColor mainColor = colorByName(
                preset.getString("name-color",
                        getConfig().getString(
                                "animation.name-title-color", "gold")));

        if (nameTitleSecs > 0) {
            showInstantTitle(
                    Component.text(presetName)
                            .color(mainColor)
                            .decorate(TextDecoration.BOLD),
                    Component.empty(),
                    nameTitleSecs * MS_PER_SECOND);
            playSoundForAll(Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
        }

        // ガチャ名表示が終わってからスロット回転（または即決）を開始
        final Runnable start = () -> {
            if (rollSeconds <= 0) {
                revealRank(presetId, entries, total, tiers,
                        winnerKey, playerName);
                return;
            }
            final long now = System.currentTimeMillis();
            spin(presetId, entries, keys, total, tiers,
                    winnerKey, playerName,
                    now, now + rollSeconds * MS_PER_SECOND);
        };

        if (nameTitleSecs > 0) {
            Bukkit.getScheduler().runTaskLater(
                    this, start, nameTitleSecs * TICKS_PER_SECOND);
        } else {
            start.run();
        }
    }

    /**
     * スロット回転中の再帰処理。終端に近づくほど間隔が遅くなる。
     *
     * @param presetId   プリセットID
     * @param entries    エントリセクション
     * @param keys       エントリキーリスト
     * @param total      weight 合計
     * @param tiers      レア度リスト
     * @param winnerKey  当選キー
     * @param playerName 実行者名
     * @param start      スロット開始時刻（ms）
     * @param end        スロット終了時刻（ms）
     */
    private void spin(final String presetId,
                      final ConfigurationSection entries,
                      final List<String> keys,
                      final double total,
                      final List<Tier> tiers,
                      final String winnerKey,
                      final String playerName,
                      final long start, final long end) {
        final long now = System.currentTimeMillis();
        if (now >= end) {
            revealRank(presetId, entries, total, tiers,
                    winnerKey, playerName);
            return;
        }

        // ランダムなエントリを一瞬表示（スロット演出）
        final String key = keys.get(
                ThreadLocalRandom.current().nextInt(keys.size()));
        final ConfigurationSection e =
                entries.getConfigurationSection(key);
        if (e != null) {
            final double chance =
                    Math.max(0.0, e.getDouble("weight", 1.0))
                    / total * 100.0;
            final Tier tier = tierFor(chance, tiers);
            final String name = e.getString("name", key);
            // 回転中は星もレア度色で表示（結果表示の星は黄色のまま）
            if (tier.rainbow) {
                showInstantTitle(
                        rainbowText(name, 0, true),
                        rainbowText(buildStarString(tier.stars), 0, false),
                        SLOT_DISPLAY_MS);
            } else {
                showInstantTitle(
                        Component.text(name)
                                .color(tier.color)
                                .decorate(TextDecoration.BOLD),
                        Component.text(buildStarString(tier.stars))
                                .color(tier.color),
                        SLOT_DISPLAY_MS);
            }
        }
        playSoundForAll(Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.2f);

        // 経過割合に応じてディレイを増加（終端で減速）
        final double fraction =
                (double) (now - start) / (double) (end - start);
        final long delay =
                SPIN_MIN_DELAY_TICKS
                + Math.round(SPIN_DELAY_RANGE_TICKS * fraction);

        Bukkit.getScheduler().runTaskLater(this,
                () -> spin(presetId, entries, keys, total, tiers,
                        winnerKey, playerName, start, end),
                delay);
    }

    // ── 演出フェーズ ──

    /**
     * 第1段階: レア度ランク（SSR等）を大きく表示する。
     *
     * <p>表示後、{@link #RANK_TO_WIN_DELAY_TICKS} tick 後に
     * 第2段階（{@link #revealWinner}）へ遷移する。</p>
     *
     * @param presetId   プリセットID
     * @param entries    エントリセクション
     * @param total      weight 合計
     * @param tiers      レア度リスト
     * @param winnerKey  当選キー
     * @param playerName 実行者名
     */
    private void revealRank(final String presetId,
                            final ConfigurationSection entries,
                            final double total,
                            final List<Tier> tiers,
                            final String winnerKey,
                            final String playerName) {
        final ConfigurationSection won =
                entries.getConfigurationSection(winnerKey);
        if (won == null) {
            rolling = false;
            return;
        }

        final double chance =
                Math.max(0.0, won.getDouble("weight", 1.0))
                / total * 100.0;
        final Tier tier = tierFor(chance, tiers);
        final String starStr = buildStarString(tier.stars);

        if (tier.rainbow) {
            // 当選表示（revealWinner）に被せないよう、遷移までの時間でアニメを打ち切る
            showRainbowTitle(tier.rank, starStr,
                    RANK_TO_WIN_DELAY_TICKS * MS_PER_TICK);
        } else {
            showInstantTitle(
                    Component.text(tier.rank)
                            .color(tier.color)
                            .decorate(TextDecoration.BOLD),
                    Component.text(starStr).color(NamedTextColor.YELLOW),
                    RANK_DISPLAY_MS);
        }
        playSoundForAll(Sound.BLOCK_NOTE_BLOCK_BELL,
                1.0f, pitchByStars(tier.stars));

        getLogger().info("[gacha] ランク確定 preset=" + presetId
                + " rank=" + tier.rank
                + " chance=" + String.format(Locale.ROOT, "%.2f", chance)
                + "%");

        Bukkit.getScheduler().runTaskLater(this,
                () -> revealWinner(presetId, won, tier,
                        winnerKey, playerName),
                RANK_TO_WIN_DELAY_TICKS);
    }

    /**
     * 第2段階: 当選内容の表示、効果音、アナウンス、コマンド実行。
     *
     * @param presetId  プリセットID
     * @param won       当選エントリセクション
     * @param tier      該当レア度
     * @param winnerKey 当選キー
     * @param playerName 実行者名
     */
    private void revealWinner(final String presetId,
                              final ConfigurationSection won,
                              final Tier tier,
                              final String winnerKey,
                              final String playerName) {
        final String displayName = won.getString("name", winnerKey);
        final String starStr = buildStarString(tier.stars);

        // 当選タイトル表示（サブタイトルは星のみ・ランク表記なし）
        final Sound winSnd = winSound(tier.stars);
        if (tier.rainbow) {
            showRainbowTitle(displayName, starStr, WIN_STAY_MS);
        } else {
            final Component main = Component.text(displayName)
                    .color(tier.color)
                    .decorate(TextDecoration.BOLD);
            final Component sub = Component.text(starStr)
                    .color(NamedTextColor.YELLOW);
            final Title.Times times = Title.Times.times(
                    Duration.ofMillis(WIN_FADE_IN_MS),
                    Duration.ofMillis(WIN_STAY_MS),
                    Duration.ofMillis(WIN_FADE_OUT_MS));
            final Title title = Title.title(main, sub, times);
            for (final Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(title);
            }
        }
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), winSnd, 1.0f, 1.0f);
        }
        playRarityEffect(tier.stars);

        getLogger().info("[gacha] 当選確定 preset=" + presetId
                + " 実行者=" + playerName
                + " entry=" + winnerKey
                + " name=" + displayName
                + " rank=" + tier.rank
                + " stars=" + tier.stars);

        broadcastWinMessage(presetId, tier, displayName,
                playerName, starStr);
        executeCommands(won, playerName);

        final String chainId = won.getString("chain", "").trim();
        if (!chainId.isEmpty()) {
            scheduleChain(chainId, playerName);
            return;  // rolling フラグは連鎖側で管理する
        }

        rolling = false;
    }

    /**
     * 連鎖（chain）抽選を予約する。
     *
     * <p>当選タイトルを読み切れるよう {@code animation.chain-delay-seconds} 秒
     * （既定 {@value #DEFAULT_CHAIN_DELAY_SECONDS} 秒）待ってから、
     * 指定プリセットをコンソール実行で連続抽選する。実行者名は引き継ぐ。</p>
     *
     * <p>連鎖先プリセットが存在しない場合や、連鎖段数が
     * {@value #MAX_CHAIN_DEPTH} を超えた場合（config ミスによる
     * 無限ループ防止）は警告ログを出して連鎖を打ち切る。</p>
     *
     * @param chainId    連鎖先プリセットID
     * @param playerName 引き継ぐ実行者名
     */
    private void scheduleChain(final String chainId, final String playerName) {
        if (chainDepth >= MAX_CHAIN_DEPTH) {
            getLogger().warning("[gacha] 連鎖が " + MAX_CHAIN_DEPTH
                    + " 段を超えたため打ち切りました"
                    + "（config の chain 設定がループしていないか確認）。");
            rolling = false;
            return;
        }
        if (getConfig().getConfigurationSection("presets." + chainId) == null) {
            getLogger().warning("[gacha] 連鎖先プリセット '" + chainId
                    + "' が見つかりません。連鎖を中止します。");
            rolling = false;
            return;
        }

        final int delaySecs = Math.max(0, getConfig().getInt(
                "animation.chain-delay-seconds", DEFAULT_CHAIN_DELAY_SECONDS));
        chainDepth++;
        getLogger().info("[gacha] 連鎖抽選 → preset=" + chainId
                + " (" + delaySecs + "秒後・" + chainDepth + "段目)");

        Bukkit.getScheduler().runTaskLater(this, () -> {
            rolling = false;  // rollGacha 内で再度 true になる
            rollGacha(Bukkit.getConsoleSender(), chainId, null, playerName);
        }, delaySecs * TICKS_PER_SECOND);
    }

    /**
     * 当選アナウンスを全体チャットに送信する。
     *
     * @param presetId    プリセットID
     * @param tier        該当レア度
     * @param displayName 当選表示名
     * @param playerName  実行者名
     * @param starStr     星文字列
     */
    private void broadcastWinMessage(final String presetId,
                                     final Tier tier,
                                     final String displayName,
                                     final String playerName,
                                     final String starStr) {
        final String announce = getConfig().getString("messages.win",
                "§6[ガチャ] §f%player% §6は"
                + " §e%name% %stars%"
                + " §6を引き当てた！");
        final String presetName = getConfig().getString(
                "presets." + presetId + ".name", presetId);

        // 虹色レア（UR等）の %rank% はチャットでも1文字ずつ虹色にする
        final String rankStr = tier.rainbow
                ? rainbowLegacy(tier.rank)
                : tier.rank;

        Bukkit.broadcastMessage(announce
                .replace("%presets%", presetName)
                .replace("%preset%", presetName)
                .replace("%rank%", rankStr)
                .replace("%name%", displayName)
                .replace("%player%", playerName)
                .replace("%stars%", starStr));
    }

    /**
     * 当選エントリの commands をコンソール権限で順次実行する。
     *
     * @param won        当選エントリセクション
     * @param playerName 実行者名（%player% 置換用）
     */
    private void executeCommands(final ConfigurationSection won,
                                 final String playerName) {
        for (final String cmd : won.getStringList("commands")) {
            if (cmd == null || cmd.trim().isEmpty()) {
                continue;
            }
            String resolved = cmd.replace("%player%", playerName).trim();
            if (resolved.startsWith("/")) {
                resolved = resolved.substring(1);
            }
            final boolean ok = Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(), resolved);
            getLogger().info("[gacha] 実行: /" + resolved
                    + " -> " + (ok
                            ? "成功"
                            : "失敗/不明コマンド"));
        }
    }

    // ── 花火・パーティクルエフェクト ──

    /**
     * 高レア度（星4以上）の当選時に花火とパーティクルを発生させる。
     *
     * <p>星6（UR）: 5発・虹7色・STAR型、星5: 3発・金色・STAR型、
     * 星4: 1発・紫・BALL_LARGE型。星3以下では演出なし。</p>
     *
     * @param stars 当選の星数
     */
    private void playRarityEffect(final int stars) {
        if (stars < FIREWORK_MIN_STARS) {
            return;
        }

        final int bursts;
        final Color[] colors;
        final FireworkEffect.Type type;
        if (stars >= STARS_UR) {
            bursts = FIREWORK_BURST_STAR6;
            colors = FIREWORK_COLORS_RAINBOW;
            type = FireworkEffect.Type.STAR;
        } else if (stars >= SOUND_THRESHOLD_HIGH) {
            bursts = FIREWORK_BURST_STAR5;
            colors = new Color[] {FIREWORK_COLOR_GOLD};
            type = FireworkEffect.Type.STAR;
        } else {
            bursts = FIREWORK_BURST_STAR4;
            colors = new Color[] {FIREWORK_COLOR_PURPLE};
            type = FireworkEffect.Type.BALL_LARGE;
        }

        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                    p.getLocation().add(0, PARTICLE_Y_OFFSET, 0),
                    RARITY_PARTICLE_COUNT,
                    PARTICLE_OFFSET_X, PARTICLE_OFFSET_Y,
                    PARTICLE_OFFSET_Z, PARTICLE_SPEED);
            p.playSound(p.getLocation(),
                    Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 1.0f);

            for (int i = 0; i < bursts; i++) {
                spawnFirework(p, type, colors);
            }
        }
    }

    /**
     * プレイヤーの位置に花火を1発打ち上げる。
     *
     * @param player 対象プレイヤー
     * @param type   花火の型
     * @param colors 花火の色（複数指定可）
     */
    private void spawnFirework(final Player player,
                               final FireworkEffect.Type type,
                               final Color... colors) {
        final Firework fw = player.getWorld().spawn(
                player.getLocation(), Firework.class);
        final FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(colors)
                .withFade(Color.WHITE)
                .with(type)
                .trail(true)
                .flicker(true)
                .build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
    }

    // ── 演出プレビュー ──

    /**
     * 指定の星ランクの演出だけを再生する（抽選なし・commands 非実行）。
     *
     * @param sender コマンド実行者
     * @param stars  星数（1-6）
     */
    private void runPreview(final CommandSender sender, final int stars) {
        if (rolling) {
            sender.sendMessage(
                    "§eガチャ実行中です。"
                    + "少し待ってください。");
            return;
        }

        final List<Tier> tiers = loadTiers();
        final Tier tier = tierByStars(stars, tiers);
        final String starStr = buildStarString(tier.stars);

        rolling = true;
        sender.sendMessage("§7[test] ランク §e" + tier.rank
                + " §7(⭐" + tier.stars
                + ") の演出を再生します。");

        // 第1段階: ランク表記
        if (tier.rainbow) {
            showRainbowTitle(tier.rank, starStr,
                    RANK_TO_WIN_DELAY_TICKS * MS_PER_TICK);
        } else {
            showInstantTitle(
                    Component.text(tier.rank)
                            .color(tier.color)
                            .decorate(TextDecoration.BOLD),
                    Component.text(starStr).color(NamedTextColor.YELLOW),
                    RANK_DISPLAY_MS);
        }
        playSoundForAll(Sound.BLOCK_NOTE_BLOCK_BELL,
                1.0f, pitchByStars(tier.stars));

        // 第2段階: ダミー当選表示 + エフェクト
        Bukkit.getScheduler().runTaskLater(this, () -> {
            final Sound ws = winSound(tier.stars);
            if (tier.rainbow) {
                showRainbowTitle("テスト演出", starStr, WIN_STAY_MS);
            } else {
                final Component main = Component.text(
                        "テスト演出")
                        .color(tier.color)
                        .decorate(TextDecoration.BOLD);
                final Component sub = Component.text(starStr)
                        .color(NamedTextColor.YELLOW);
                final Title.Times times = Title.Times.times(
                        Duration.ofMillis(WIN_FADE_IN_MS),
                        Duration.ofMillis(WIN_STAY_MS),
                        Duration.ofMillis(WIN_FADE_OUT_MS));
                final Title title = Title.title(main, sub, times);
                for (final Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(title);
                }
            }
            for (final Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(p.getLocation(), ws, 1.0f, 1.0f);
            }
            playRarityEffect(tier.stars);
            rolling = false;
        }, RANK_TO_WIN_DELAY_TICKS);
    }

    // ── レア度判定ユーティリティ ──

    /**
     * config の rarity-tiers を読み込み、maxChance 昇順に並べて返す。
     *
     * <p>color に {@code RAINBOW} を指定したランクは虹色アニメ表示になる。
     * 未定義時はデフォルトの6段階（UR/HR/SSR/SR/R/N）にフォールバックする。</p>
     *
     * @return Tier リスト（maxChance 昇順＝厳しい順）
     */
    private List<Tier> loadTiers() {
        final List<Tier> tiers = new ArrayList<>();
        try {
            for (final Map<?, ?> m : getConfig().getMapList("rarity-tiers")) {
                final String rank = String.valueOf(m.get("rank"));
                final double maxChance = toDouble(m.get("max-chance"), 100.0);
                final int starN = (int) toDouble(m.get("stars"), 1.0);
                final String colorName = String.valueOf(m.get("color"));
                final boolean rainbow =
                        "rainbow".equalsIgnoreCase(colorName.trim());
                final NamedTextColor color = colorByName(colorName);
                tiers.add(new Tier(rank, maxChance, starN, color, rainbow));
            }
        } catch (final Exception e) {
            getLogger().warning(
                    "rarity-tiers の読み込みに失敗。"
                    + "デフォルトを使用: "
                    + e.getMessage());
            tiers.clear();
        }

        if (tiers.isEmpty()) {
            tiers.add(new Tier("UR", 0.5, 6, NamedTextColor.WHITE, true));
            tiers.add(new Tier("HR", 1.0, 5, NamedTextColor.GOLD, false));
            tiers.add(new Tier("SSR", 5.0, 4,
                    NamedTextColor.LIGHT_PURPLE, false));
            tiers.add(new Tier("SR", 15.0, 3, NamedTextColor.AQUA, false));
            tiers.add(new Tier("R", 40.0, 2, NamedTextColor.GREEN, false));
            tiers.add(new Tier("N", 100.0, 1, NamedTextColor.GRAY, false));
        }
        tiers.sort(Comparator.comparingDouble(a -> a.maxChance));
        return tiers;
    }

    /**
     * 当選確率に対応するレア度を返す。
     *
     * <p>確率が低いほど高ランク。maxChance 以下になる最初のランクを返す。</p>
     *
     * @param chance 当選確率(%)
     * @param tiers  Tier リスト（maxChance 昇順）
     * @return 該当 Tier
     */
    private Tier tierFor(final double chance, final List<Tier> tiers) {
        for (final Tier t : tiers) {
            if (chance <= t.maxChance) {
                return t;
            }
        }
        return tiers.get(tiers.size() - 1);
    }

    /**
     * 指定の星数に最も近い Tier を返す。
     *
     * @param stars 星数
     * @param tiers Tier リスト
     * @return 完全一致する Tier、なければ最も近い Tier
     */
    private Tier tierByStars(final int stars, final List<Tier> tiers) {
        Tier best = tiers.get(tiers.size() - 1);
        int bestDiff = Integer.MAX_VALUE;
        for (final Tier t : tiers) {
            if (t.stars == stars) {
                return t;
            }
            final int diff = Math.abs(t.stars - stars);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = t;
            }
        }
        return best;
    }

    // ── 汎用ユーティリティ ──

    /**
     * 文字列を整数にパースする。失敗時は null を返す。
     *
     * @param s パース対象文字列
     * @return パース結果、または null
     */
    private Integer parseIntOrNull(final String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * 全オンラインプレイヤーにタイトルを即時表示する（fadeIn/fadeOut なし）。
     *
     * @param main   メインタイトル
     * @param sub    サブタイトル
     * @param stayMs 表示時間（ミリ秒）
     */
    private void showInstantTitle(final Component main,
                                  final Component sub,
                                  final long stayMs) {
        final Title.Times times = Title.Times.times(
                Duration.ZERO, Duration.ofMillis(stayMs), Duration.ZERO);
        final Title title = Title.title(main, sub, times);
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
        }
    }

    // ── 虹色（RAINBOW）表示ユーティリティ ──

    /**
     * 文字列を1文字ずつ色相をずらした虹色グラデーションの Component にする。
     *
     * @param text  対象文字列
     * @param frame アニメフレーム番号（0 で静止画。増やすと色相が流れる）
     * @param bold  太字にするかどうか
     * @return 虹色グラデーションの Component
     */
    private Component rainbowText(final String text, final int frame,
                                  final boolean bold) {
        final TextComponent.Builder b = Component.text();
        final float base = frame * RAINBOW_HUE_SHIFT_PER_FRAME;
        for (int i = 0; i < text.length(); i++) {
            final float hue =
                    (base + i * RAINBOW_HUE_STEP) % 1.0f;
            Component c = Component.text(String.valueOf(text.charAt(i)))
                    .color(TextColor.color(HSVLike.hsvLike(
                            hue, RAINBOW_SATURATION, RAINBOW_VALUE)));
            if (bold) {
                c = c.decorate(TextDecoration.BOLD);
            }
            b.append(c);
        }
        return b.build();
    }

    /**
     * 虹色アニメーションのタイトルを全オンラインプレイヤーに表示する。
     *
     * <p>{@link #RAINBOW_FRAME_TICKS} tick ごとに色相をずらしたタイトルを
     * 再送し、色が流れて見えるようにする。stayMs 経過で自動終了する。</p>
     *
     * @param mainText メインタイトル文字列（太字・虹色）
     * @param subText  サブタイトル文字列（虹色）
     * @param stayMs   合計表示時間（ミリ秒）
     */
    private void showRainbowTitle(final String mainText,
                                  final String subText,
                                  final long stayMs) {
        rainbowFrame(mainText, subText, stayMs, 0);
    }

    /**
     * 虹色アニメの1フレームを表示し、次フレームを予約する再帰処理。
     *
     * @param mainText メインタイトル文字列
     * @param subText  サブタイトル文字列
     * @param stayMs   合計表示時間（ミリ秒）
     * @param frame    現在のフレーム番号
     */
    private void rainbowFrame(final String mainText,
                              final String subText,
                              final long stayMs,
                              final int frame) {
        final long elapsed = frame * RAINBOW_FRAME_TICKS * MS_PER_TICK;
        if (elapsed >= stayMs) {
            return;
        }
        final long remain = stayMs - elapsed;
        showInstantTitle(
                rainbowText(mainText, frame, true),
                rainbowText(subText, frame, false),
                Math.min(RAINBOW_FRAME_STAY_MS, remain));
        Bukkit.getScheduler().runTaskLater(this,
                () -> rainbowFrame(mainText, subText, stayMs, frame + 1),
                RAINBOW_FRAME_TICKS);
    }

    /**
     * 文字列を1文字ずつ虹色のレガシー§コードで着色する（チャット用）。
     *
     * @param text 対象文字列
     * @return §コード付き虹色文字列
     */
    private String rainbowLegacy(final String text) {
        final StringBuilder sb =
                new StringBuilder(text.length() * (2 + 1));
        for (int i = 0; i < text.length(); i++) {
            sb.append(RAINBOW_LEGACY_CODES[i % RAINBOW_LEGACY_CODES.length])
                    .append(text.charAt(i));
        }
        return sb.toString();
    }

    /**
     * 全オンラインプレイヤーにサウンドを再生する。
     *
     * @param sound  サウンド種別
     * @param volume 音量
     * @param pitch  ピッチ
     */
    private void playSoundForAll(final Sound sound,
                                 final float volume,
                                 final float pitch) {
        for (final Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    /**
     * オブジェクトを double に変換する。
     *
     * @param o   変換元（Number または文字列）
     * @param def パース失敗時のデフォルト値
     * @return 変換結果
     */
    private double toDouble(final Object o, final double def) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    /**
     * 色名から NamedTextColor を取得する。
     *
     * @param name 色名（null 可、大文字小文字不問）
     * @return 該当する NamedTextColor（不明時は WHITE）
     */
    private NamedTextColor colorByName(final String name) {
        if (name == null) {
            return NamedTextColor.WHITE;
        }
        final NamedTextColor c =
                NamedTextColor.NAMES.value(name.toLowerCase(Locale.ROOT));
        return (c != null) ? c : NamedTextColor.WHITE;
    }

    /**
     * 指定数の星絵文字文字列を生成する。
     *
     * @param n 星数（0〜{@link #MAX_STAR_DISPLAY} にクランプ）
     * @return 星絵文字の文字列
     */
    private String buildStarString(final int n) {
        final int count = Math.max(0, Math.min(n, MAX_STAR_DISPLAY));
        final StringBuilder sb = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            sb.append("⭐");
        }
        return sb.toString();
    }

    /**
     * 星数に応じた効果音のピッチを計算する。
     *
     * @param stars 星数
     * @return ピッチ値（{@link #PITCH_MIN} 〜 {@link #PITCH_MAX}）
     */
    private float pitchByStars(final int stars) {
        return Math.max(PITCH_MIN,
                Math.min(PITCH_MAX, PITCH_BASE + stars * PITCH_PER_STAR));
    }

    /**
     * 星数に応じた当選効果音を返す。
     *
     * @param stars 星数
     * @return 効果音。星5以上: CHALLENGE_COMPLETE、星3以上: LEVELUP、その他: PLING
     */
    private Sound winSound(final int stars) {
        if (stars >= SOUND_THRESHOLD_HIGH) {
            return Sound.UI_TOAST_CHALLENGE_COMPLETE;
        }
        if (stars >= SOUND_THRESHOLD_MID) {
            return Sound.ENTITY_PLAYER_LEVELUP;
        }
        return Sound.BLOCK_NOTE_BLOCK_PLING;
    }
}
