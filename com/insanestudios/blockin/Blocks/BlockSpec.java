package com.insanestudios.blockin.Blocks;

import java.awt.image.BufferedImage;

/**
 * Everything the game knows about a block that is currently being registered,
 * handed to mod code so custom {@code create(BlockSpec)} factories can build a
 * configured {@link Block} without re-parsing the XML.
 *
 * <p>`build()` produces the stock block for the configured {@link #blockType}:
 * "solid"/"transparent" become a plain {@link Block}, "liquid" becomes a
 * {@link WaterBlock}. A custom java mod may also construct the block itself
 * from these fields (see {@code BlockCodeCompiler}).
 */
public final class BlockSpec {

    /** Numeric block id (clash-proof, auto-assigned when omitted). */
    public int id;
    /** Display name from the XML. */
    public String name = "";
    /** Optional description from the XML. */
    public String description = "";
    /** "solid", "transparent" or "liquid". */
    public String blockType = "solid";
    /** Atlas cell indices for top/side/bottom faces. */
    public int tileTop;
    public int tileSide;
    public int tileBottom;
    /** Optional dedicated HUD icon (falls back to the side face). */
    public BufferedImage icon;
    /** Sound family ids (default stone if empty). */
    public String soundStep = "Stone";
    public String soundPlace = "Stone";
    public String soundBreak = "Stone";

    /** Builds the stock {@link Block} for the configured type. */
    public Block build() {
        String type = blockType == null ? "solid" : blockType.toLowerCase();
        boolean liquid = "liquid".equals(type);
        Block block = icon != null
                ? (liquid
                        ? new WaterBlock(id, name, name, icon, tileTop, tileSide, tileBottom,
                                soundStep, soundPlace, soundBreak)
                        : new Block(id, name, name, icon, tileTop, tileSide, tileBottom,
                                soundStep, soundPlace, soundBreak))
                : (liquid
                        ? new WaterBlock(id, name, tileTop, tileSide, tileBottom,
                                soundStep, soundPlace, soundBreak)
                        : new Block(id, name, tileTop, tileSide, tileBottom,
                                soundStep, soundPlace, soundBreak));
        block.description = description;
        block.blockType = liquid ? "liquid" : ("transparent".equals(type) ? "transparent" : "solid");
        return block;
    }
}