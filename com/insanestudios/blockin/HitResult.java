package com.insanestudios.blockin;

/**
 * The block the crosshair is currently aimed at.
 *
 * @param x  block x
 * @param y  block y
 * @param z  block z
 * @param o  secondary selection tag (always 0 in the current picking pass)
 * @param f  the hit face (0 = bottom ... 5 = east)
 */
public record HitResult(int x, int y, int z, int o, int f) {
}