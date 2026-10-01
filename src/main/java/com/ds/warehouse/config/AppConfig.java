package com.ds.warehouse.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 全局设置。
 *
 * 存储位置：&lt;游戏实例&gt;/config/warehouse-keeper/settings.json
 * 和 regions.json 一样放在 config 而不是存档目录 —— 卸载模组后存档零残留。
 */
public final class AppConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static AppConfig instance;

    /** 进入存档时自动重新扫描一次（配合硬盘索引：先秒出旧数据，再后台刷新成最新） */
    public boolean autoScanOnWorldLoad = true;
    /** 自动扫描延迟多少秒开始，留给世界与区块加载 */
    public int autoScanDelaySeconds = 5;
    /** 开档时从硬盘恢复上次的索引，这样网页立刻有数据、不用等扫描 */
    public boolean loadIndexFromDisk = true;
    /** 扫描结束后把索引存到硬盘 */
    public boolean saveIndexToDisk = true;
    /**
     * 扫描时临时强制加载区块。
     *
     * 关掉的话，只有「已经加载」的区块会被扫到 —— 仓库离玩家很远时会出现
     * 「明明有箱子却扫不到」。打开后扫描器会为区域内的区块挂一个临时票据
     * （TicketType.UNKNOWN：会加载、不落盘、卸载即过期），扫完立刻释放。
     * 这个票据不会写进存档，卸载模组后零残留。
     */
    public boolean scanForceLoadChunks = true;

    /**
     * 要不要给搬运工一个「人形」——用 Carpet 的 /player 指令生成一个真正的假人玩家。
     *
     * 装了这个开关才有意义：没装 Carpet 时本模组照常工作，只是搬运工是隐形的。
     * 假人只存在于服务端内存里，退场时不留 playerdata；不碰存档。
     */
    public boolean porterBody = true;

    /**
     * 搬运工的值守点（它没事就站那儿）。
     *
     * 留空 = 自动用第一个仓库区域的正中心，站在地面上。
     */
    public String botHomeDim = "";
    public double botHomeX;
    public double botHomeY;
    public double botHomeZ;

    /**
     * 整理仓库的「表演节奏」：搬一件之间隔多少 tick。
     *
     * <p>1 = 一刻一件（几秒钟干完一整箱，但假人基本是在箱子里闪，看不出在干活）；
     * 4 = 每件 0.2 秒（默认：能看清它瞬移到箱子前、把箱盖打开、挥手、把货挪走）；
     * 10 = 每件 0.5 秒（慢动作，适合看着玩/录像）。
     *
     * <p>只影响观感与耗时，不影响搬运结果：每一件照样是「先算好、再原子地搬」，
     * 中途被玩家插手也不会算错；总量守恒不变。
     */
    public int tidyTicksPerMove = 4;

    public static AppConfig get() {
        AppConfig c = instance;
        if (c == null) {
            c = load();
        }
        return c;
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("warehouse-keeper")
                .resolve("settings.json");
    }

    public static AppConfig load() {
        Path f = configPath();
        AppConfig c = null;
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                c = GSON.fromJson(r, AppConfig.class);
            } catch (Exception e) {
                com.ds.warehouse.WarehouseMod.LOGGER.warn(
                        "[warehouse-keeper] 读取 settings.json 失败，先用默认值: {}", e.toString());
            }
        }
        if (c == null) {
            c = new AppConfig();
        }
        c.normalize();
        instance = c;
        return c;
    }

    public void save() {
        Path f = configPath();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            com.ds.warehouse.WarehouseMod.LOGGER.warn(
                    "[warehouse-keeper] 写入 settings.json 失败: {}", e.toString());
        }
    }

    public void normalize() {
        if (autoScanDelaySeconds < 1) {
            autoScanDelaySeconds = 1;
        }
        if (autoScanDelaySeconds > 300) {
            autoScanDelaySeconds = 300;
        }
        if (tidyTicksPerMove < 1) {
            tidyTicksPerMove = 1;
        }
        if (tidyTicksPerMove > 20) {
            tidyTicksPerMove = 20;
        }
    }
}
