pragma Singleton
import QtQuick

// Matrices and OkHSV toe mapping follow upstream color/core/Transforms.kt.
QtObject {
    function clamp(x) { return Math.max(0, Math.min(1, x)) }
    function cubeRoot(x) { return x < 0 ? -Math.pow(-x, 1 / 3) : Math.pow(x, 1 / 3) }
    function linear(x) { return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4) }
    function srgb(x) { return x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055 }
    function lab(r, g, b) {
        var l = cubeRoot(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        var m = cubeRoot(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        var s = cubeRoot(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return [0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s]
    }
    function rgb(l, a, b) {
        var x = l + 0.3963377774 * a + 0.2158037573 * b
        var y = l - 0.1055613458 * a - 0.0638541728 * b
        var z = l - 0.0894841775 * a - 1.291485548 * b
        x = x * x * x; y = y * y * y; z = z * z * z
        return [4.0767416621 * x - 3.3077115913 * y + 0.2309699292 * z,
            -1.2684380046 * x + 2.6097574011 * y - 0.3413193965 * z,
            -0.0041960863 * x - 0.7034186147 * y + 1.707614701 * z]
    }
    function toe(x) {
        var k = 1.206 / 1.03
        return 0.5 * (k * x - 0.206 + Math.sqrt((k * x - 0.206) * (k * x - 0.206) + 0.12 * k * x))
    }
    function toeInv(x) { return (x * x + 0.206 * x) / (1.206 / 1.03 * (x + 0.03)) }
    function cusp(a, b) {
        // Solve the same gamut boundary directly, avoiding hue-region polynomial tables.
        var low = 0, high = 1
        for (var i = 0; i < 24; i++) {
            var mid = (low + high) / 2
            var c = rgb(1, mid * a, mid * b)
            if (Math.min(c[0], c[1], c[2]) < 0) high = mid
            else low = mid
        }
        var edge = rgb(1, low * a, low * b)
        var l = cubeRoot(1 / Math.max(edge[0], edge[1], edge[2]))
        return [low, l * low / Math.max(0.000001, 1 - l)]
    }
    function okhsv(h, s, v, alpha) {
        if (v <= 0) return Qt.rgba(0, 0, 0, alpha)
        var a = Math.cos(h * Math.PI * 2), b = Math.sin(h * Math.PI * 2)
        var st = cusp(a, b), k = 1 - 0.5 / st[0]
        var d = 0.5 + st[1] - st[1] * k * s
        var lv = 1 - s * 0.5 / d, cv = s * st[1] * 0.5 / d
        var l = v * lv, c = v * cv
        var lvt = toeInv(lv), cvt = cv * lvt / lv
        var next = toeInv(l)
        c = c * next / l; l = next
        var edge = rgb(lvt, a * cvt, b * cvt)
        var scale = cubeRoot(1 / Math.max(edge[0], edge[1], edge[2], 0.000001))
        var out = rgb(l * scale, c * scale * a, c * scale * b)
        return Qt.rgba(clamp(srgb(out[0])), clamp(srgb(out[1])), clamp(srgb(out[2])), alpha)
    }
    function toOkhsv(color) {
        var out = lab(linear(color.r), linear(color.g), linear(color.b))
        var l = out[0], c = Math.sqrt(out[1] * out[1] + out[2] * out[2])
        if (c < 0.000001 || l < 0.000001) return [0, 0, toe(l)]
        var a = out[1] / c, b = out[2] / c
        var h = (Math.atan2(b, a) / (2 * Math.PI) + 1) % 1
        var st = cusp(a, b), k = 1 - 0.5 / st[0]
        var t = st[1] / (c + l * st[1]), lv = t * l, cv = t * c
        var lvt = toeInv(lv), cvt = cv * lvt / lv
        var edge = rgb(lvt, a * cvt, b * cvt)
        var scale = cubeRoot(1 / Math.max(edge[0], edge[1], edge[2], 0.000001))
        return [h, clamp((0.5 + st[1]) * cv / (st[1] * 0.5 + st[1] * k * cv)), clamp(toe(l / scale) / lv)]
    }
    function fromChannels(space, x, y, z, alpha) {
        if (space === "OKHSV") return okhsv(x, y, z, alpha)
        var out = space === "OKLCH" ? rgb(x, y * 0.4 * Math.cos(z * Math.PI * 2), y * 0.4 * Math.sin(z * Math.PI * 2))
            : rgb(x, (y * 2 - 1) * 0.4, (z * 2 - 1) * 0.4)
        return Qt.rgba(clamp(out[0]), clamp(out[1]), clamp(out[2]), alpha)
    }
    function channels(space, color) {
        if (space === "OKHSV") return toOkhsv(color)
        var out = lab(color.r, color.g, color.b)
        if (space === "OKLCH") return [clamp(out[0]), clamp(Math.sqrt(out[1] * out[1] + out[2] * out[2]) / 0.4), (Math.atan2(out[2], out[1]) / (2 * Math.PI) + 1) % 1]
        return [clamp(out[0]), clamp(out[1] / 0.8 + 0.5), clamp(out[2] / 0.8 + 0.5)]
    }
}
