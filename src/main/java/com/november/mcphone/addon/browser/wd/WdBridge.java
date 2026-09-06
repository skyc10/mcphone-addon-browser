package com.november.mcphone.addon.browser.wd;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import com.november.mcphone.addon.browser.BrowserAddon;
import com.november.mcphone.core.ItemPhone;

/**
 * WebDisplays 0.11（1.7.10，包名 net.buildlight.webd）反射桥。
 *
 * <p>技术红线：不改 WD 源码、不引入编译期依赖；所有对 WD 的访问经反射完成，
 * WD 缺失时 {@link #available()} 为 false，调用方显示「前置缺失」提示，绝不崩溃。</p>
 *
 * <p>实现说明：直接反射 <b>WebDisplay.blockScreen</b> 静态字段与
 * <b>EntityWebScreen</b> 的公有成员（setURL/getURL/axd/azd），不经过 WDAPI——
 * WDAPI 需要 init() 且部分方法依赖 agent 类（代理环境下会 NPE）。</p>
 */
public final class WdBridge {

    private static volatile boolean detected;
    private static volatile boolean available;

    // ==== net.buildlight.webd.WebDisplay ====
    private static Field wdBlockScreenField;

    // ==== BlockWebScreen ====
    private static Block blockScreen;
    private static Method blockGetOrigin;
    private static Method blockGetSize;
    private static Method blockGetEntities;

    // ==== Vector2i / Vector3i ====
    private static Field vec2X;
    private static Field vec2Y;
    private static Field vec3X;
    private static Field vec3Y;
    private static Field vec3Z;

    // ==== EntityWebScreen ====
    private static Constructor<?> entityCtor;
    private static Field entityAxd;
    private static Field entityAzd;
    private static Method entitySetUrl;
    private static Method entityGetUrl;

    private WdBridge() {}

    /** 是否已成功挂接 WebDisplays。 */
    public static boolean available() {
        return available;
    }

    /** 探测并缓存全部反射句柄；失败只标记 available=false。可安全重复调用。 */
    public static synchronized void detect() {
        if (detected) return;
        detected = true;
        try {
            ClassLoader cl = WdBridge.class.getClassLoader();
            Class<?> wdMain = cl.loadClass("net.buildlight.webd.WebDisplay");
            wdBlockScreenField = wdMain.getField("blockScreen");
            blockScreen = (Block) wdBlockScreenField.get(null);
            if (blockScreen == null) {
                // 方块尚未注册（加载顺序），留到 postInit 重试。
                detected = false;
                available = false;
                return;
            }

            Class<?> blockClass = cl.loadClass("net.buildlight.webd.block.BlockWebScreen");
            blockGetOrigin = blockClass.getMethod("getOrigin", IBlockAccess.class, int.class, int.class, int.class, int.class);
            blockGetSize = blockClass.getMethod("getSize", World.class, int.class, int.class, int.class, int.class);
            blockGetEntities = blockClass.getMethod("getEntities", World.class, int.class, int.class, int.class);

            Class<?> vec2Class = cl.loadClass("net.buildlight.webd.Vector2i");
            vec2X = vec2Class.getField("x");
            vec2Y = vec2Class.getField("y");
            Class<?> vec3Class = cl.loadClass("net.buildlight.webd.Vector3i");
            vec3X = vec3Class.getField("x");
            vec3Y = vec3Class.getField("y");
            vec3Z = vec3Class.getField("z");

            Class<?> entityClass = cl.loadClass("net.buildlight.webd.entity.EntityWebScreen");
            entityCtor = entityClass.getConstructor(World.class, double.class, double.class, double.class, vec2Class);
            entityAxd = entityClass.getField("axd");
            entityAzd = entityClass.getField("azd");
            entitySetUrl = entityClass.getMethod("setURL", String.class);
            entityGetUrl = entityClass.getMethod("getURL");

            available = true;
            System.out.println("[mcphone_browser] WebDisplays bridge ready (blockScreen=" + blockScreen + ")");
        } catch (Throwable t) {
            available = false;
            System.out.println("[mcphone_browser] WebDisplays bridge unavailable: " + t);
        }
    }

    // ===================== URL 规范化 =====================

    /** 无协议时补 https://（Bing / GTNH 均为 https）。 */
    public static String normalize(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "https://" + s;
        return s;
    }

    // ===================== 放置 / 恢复大屏 =====================

    /**
     * 在玩家面前 3 格放置 3x2 屏幕墙并加载 url。
     *
     * @return null = 成功；否则返回错误 key（供本地化）。
     */
    public static String placeScreen(EntityPlayer player, String url) {
        if (!available()) return "missing_wd";
        World world = player.worldObj;
        try {
            // 玩家水平朝向：0=+Z(南) 1=-X(西) 2=-Z(北) 3=+X(东)
            int yaw = (int) Math.floor(player.rotationYaw * 4.0 / 360.0 + 0.5) & 3;
            int fx = (yaw == 1) ? -1 : (yaw == 3) ? 1 : 0;
            int fz = (yaw == 0) ? 1 : (yaw == 2) ? -1 : 0;
            int sx = fz; // 垂直于视线的水平方向（墙体展开方向）
            int sz = fx;
            // 面向玩家的那一面：南(0)→北面(2) 西(1)→东面(5) 北(2)→南面(3) 东(3)→西面(4)
            int side = (yaw == 0) ? 2 : (yaw == 1) ? 5 : (yaw == 2) ? 3 : 4;

            int dist = 3;
            int px = (int) Math.floor(player.posX);
            int py = (int) Math.floor(player.posY);
            int pz = (int) Math.floor(player.posZ);
            final int W = 3;
            final int H = 2;

            List<int[]> cells = new ArrayList<>();
            for (int c = -1; c <= 1; c++) {
                for (int r = 0; r < H; r++) {
                    int x = px + fx * dist + sx * c;
                    int y = py + 1 + r;
                    int z = pz + fz * dist + sz * c;
                    if (!world.blockExists(x, y, z)) return "no_space";
                    Block b = world.getBlock(x, y, z);
                    if (b != blockScreen && !world.isAirBlock(x, y, z)) return "no_space";
                    cells.add(new int[] { x, y, z });
                }
            }

            for (int[] cell : cells) {
                if (world.getBlock(cell[0], cell[1], cell[2]) != blockScreen) {
                    world.setBlock(cell[0], cell[1], cell[2], blockScreen);
                }
            }

            // 结构原点 + 尺寸（与 onBlockActivated 相同的服务端逻辑）
            int[] any = cells.get(cells.size() / 2);
            Object v3 = blockGetOrigin.invoke(blockScreen, world, any[0], any[1], any[2], side);
            if (v3 == null) return "bad_shape";
            int ox = vec3X.getInt(v3);
            int oy = vec3Y.getInt(v3);
            int oz = vec3Z.getInt(v3);
            Object v2 = blockGetSize.invoke(blockScreen, world, ox, oy, oz, side);
            if (v2 == null) return "bad_shape";
            int w = vec2X.getInt(v2);
            int h = vec2Y.getInt(v2);

            // 已有屏幕实体 → 复用；否则创建（复刻 onBlockActivated 创建序列）
            @SuppressWarnings("unchecked")
            List<Entity> ents = (List<Entity>) blockGetEntities.invoke(null, world, ox, oy, oz);
            Entity ent = (ents != null && !ents.isEmpty()) ? ents.get(0) : null;
            if (ent == null) {
                double axd;
                double azd;
                float yawDeg;
                switch (side) {
                    case 2:
                        azd = -0.001;
                        axd = 0;
                        yawDeg = 180.0f;
                        break;
                    case 3:
                        azd = 0.001;
                        axd = 0;
                        yawDeg = 0.0f;
                        break;
                    case 4:
                        axd = -0.001;
                        azd = 0;
                        yawDeg = -90.0f;
                        break;
                    case 5:
                    default:
                        axd = 0.001;
                        azd = 0;
                        yawDeg = 90.0f;
                        break;
                }
                Object inst = entityCtor.newInstance(world, (double) ox, (double) oy, (double) oz, v2);
                entityAxd.setDouble(inst, axd);
                entityAzd.setDouble(inst, azd);
                Entity e = (Entity) inst;
                e.rotationYaw = yawDeg;
                e.rotationPitch = 0.0f;
                if (!world.spawnEntityInWorld(e)) return "spawn_failed";
                ent = e;
            }

            entitySetUrl.invoke(ent, url);

            // 记录到手机 NBT（服务端写，随物品同步回客户端）
            ItemStack phone = findPhone(player);
            if (phone != null) {
                NBTTagCompound root = phone.hasTagCompound() ? phone.getTagCompound() : new NBTTagCompound();
                root.setString(BrowserAddon.NBT_LAST_URL, url);
                NBTTagCompound sc = new NBTTagCompound();
                sc.setInteger("dim", player.dimension);
                sc.setInteger("ox", ox);
                sc.setInteger("oy", oy);
                sc.setInteger("oz", oz);
                sc.setInteger("w", w);
                sc.setInteger("h", h);
                sc.setInteger("side", side);
                root.setTag(BrowserAddon.NBT_SCREEN, sc);
                phone.setTagCompound(root);
            }
            return null;
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] placeScreen failed: " + t);
            return "internal_error";
        }
    }

    // ===================== 隐藏大屏 =====================

    /**
     * 隐藏（移除）当前大屏：杀实体 + 洪泛移除屏幕方块。URL 保留在手机 NBT。
     *
     * @return null = 成功；否则错误 key。
     */
    public static String hideScreen(EntityPlayer player) {
        ItemStack phone = findPhone(player);
        if (phone == null) return "no_phone";
        if (!phone.hasTagCompound() || !phone.getTagCompound().hasKey(BrowserAddon.NBT_SCREEN)) {
            return "not_bound";
        }
        if (!available()) return "missing_wd";
        NBTTagCompound sc = phone.getTagCompound().getCompoundTag(BrowserAddon.NBT_SCREEN);
        int dim = sc.getInteger("dim");
        if (dim != player.dimension) return "wrong_dim";

        World world = player.worldObj;
        int ox = sc.getInteger("ox");
        int oy = sc.getInteger("oy");
        int oz = sc.getInteger("oz");

        try {
            @SuppressWarnings("unchecked")
            List<Entity> ents = (List<Entity>) blockGetEntities.invoke(null, world, ox, oy, oz);
            if (ents != null) {
                for (Entity e : ents) {
                    e.setDead();
                }
            }
            Set<Long> visited = new HashSet<>();
            List<int[]> queue = new ArrayList<>();
            queue.add(new int[] { ox, oy, oz });
            visited.add(key(ox, oy, oz));
            int removed = 0;
            while (!queue.isEmpty() && removed < 64) {
                int[] cur = queue.remove(queue.size() - 1);
                if (world.getBlock(cur[0], cur[1], cur[2]) == blockScreen) {
                    world.setBlockToAir(cur[0], cur[1], cur[2]);
                    removed++;
                    int[][] dirs = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
                    for (int[] d : dirs) {
                        int nx = cur[0] + d[0];
                        int ny = cur[1] + d[1];
                        int nz = cur[2] + d[2];
                        long k = key(nx, ny, nz);
                        if (visited.add(k)) {
                            queue.add(new int[] { nx, ny, nz });
                        }
                    }
                }
            }
            NBTTagCompound root = phone.getTagCompound();
            root.removeTag(BrowserAddon.NBT_SCREEN);
            phone.setTagCompound(root);
            return removed > 0 ? null : "not_found";
        } catch (Throwable t) {
            System.err.println("[mcphone_browser] hideScreen failed: " + t);
            return "internal_error";
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (long) (y & 0xFFF);
    }

    // ===================== 手机 NBT =====================

    /** 在玩家背包中找手机（服务端权威副本）。 */
    public static ItemStack findPhone(EntityPlayer player) {
        for (ItemStack s : player.inventory.mainInventory) {
            if (s != null && s.getItem() instanceof ItemPhone) return s;
        }
        return null;
    }

    // ===================== 屏幕当前 URL =====================

    public static String screenUrl(Entity ent) {
        if (!available) return null;
        try {
            Object out = entityGetUrl.invoke(ent);
            return out instanceof String ? (String) out : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
