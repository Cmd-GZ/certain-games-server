package com.memoryflip;

import org.bukkit.Material;
import org.bukkit.entity.ItemFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个玩家在这一局里的翻牌进度（D22：从 MemoryFlipManager 的私有内部类提出来，
 * 让"每房间一份的 MemoryGame"能持有它）。
 */
final class PlayState {
    int boardIndex;
    final List<ItemFrame> frames = new ArrayList<>();
    Material[] items = new Material[0];
    boolean[] matched = new boolean[0];
    int pairs;
    int firstIdx = -1;
    boolean reviewing;
    long finishedAt = -1L;

    boolean finished() {
        return finishedAt > 0;
    }
}
