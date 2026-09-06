package com.november.mcphone.addon.browser.core;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.november.mcphone.addon.browser.BrowserAddon;

/**
 * 手机 ItemStack NBT 读取（客户端，从同步过来的物品副本读）。
 * 写入只发生在服务端（WdBridge），保证重进存档后仍然保留。
 */
public final class PhoneNbt {

    private PhoneNbt() {}

    /** 读取上次 URL（未绑定时返回 null）。 */
    public static String readLastUrl(ItemStack phone) {
        if (phone == null || !phone.hasTagCompound()) return null;
        NBTTagCompound root = phone.getTagCompound();
        if (!root.hasKey(BrowserAddon.NBT_LAST_URL)) return null;
        String url = root.getString(BrowserAddon.NBT_LAST_URL);
        return url == null || url.isEmpty() ? null : url;
    }
}
