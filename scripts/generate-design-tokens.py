#!/usr/bin/env python3
"""Validate and export tPlanner's small light-token package without dependencies.

Run normally to regenerate, or pass --check to verify committed exports. Custom
--source and --output paths allow isolated validation without touching the package.
This intentionally supports only the token types used by this repository.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT / "design-assets/tokens/tplanner-light.tokens.json"
DEFAULT_OUTPUT = ROOT / "design-assets/tokens/generated"
SUPPORTED_TYPES = {
    "color", "dimension", "number", "fontWeight", "fontFamily", "duration", "cubicBezier",
}
ALIAS = re.compile(r"^\{([^{}]+)\}$")
HEX_COLOR = re.compile(r"^#[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?$")
MAX_FLOAT = 3.4028234663852886e38


class TokenError(ValueError):
    pass


@dataclass(frozen=True)
class Token:
    path: tuple[str, ...]
    kind: str
    value: Any

    @property
    def name(self) -> str:
        return ".".join(self.path)


def fail(message: str) -> None:
    raise TokenError(message)


def number(value: Any, where: str, *, float_target: bool = False) -> int | float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        fail(f"{where}: expected a finite number")
    try:
        finite = math.isfinite(value)
    except OverflowError:
        finite = False
    if not finite or (float_target and abs(value) > MAX_FLOAT):
        fail(f"{where}: number cannot be represented by the export target")
    return value


def byte(value: float) -> int:
    return int(math.floor(value * 255 + 0.5))


def color_hex(channels: list[int]) -> str:
    rgb = "#" + "".join(f"{value:02X}" for value in channels[:3])
    return rgb if channels[3] == 255 else rgb + f"{channels[3]:02X}"


def color_channels(value: str) -> list[int]:
    result = [int(value[index:index + 2], 16) for index in (1, 3, 5)]
    return result + [int(value[7:9], 16) if len(value) == 9 else 255]


def validate_value(kind: str, value: Any, where: str) -> Any:
    if kind == "color":
        if not isinstance(value, dict) or value.get("colorSpace") != "srgb":
            fail(f"{where}: color must be an sRGB color object")
        components = value.get("components")
        if not isinstance(components, list) or len(components) != 3:
            fail(f"{where}: sRGB components must contain three numbers")
        for index, component in enumerate(components):
            number(component, f"{where}.components[{index}]")
            if not 0 <= component <= 1:
                fail(f"{where}: sRGB components must be in [0, 1]")
        alpha = number(value.get("alpha", 1), f"{where}.alpha")
        if not 0 <= alpha <= 1:
            fail(f"{where}: alpha must be in [0, 1]")
        channels = [byte(component) for component in components] + [byte(alpha)]
        if "hex" in value:
            declared = value["hex"]
            if not isinstance(declared, str) or HEX_COLOR.fullmatch(declared) is None:
                fail(f"{where}: hex must be #RRGGBB or #RRGGBBAA")
            declared_channels = color_channels(declared)
            if declared_channels[:3] != channels[:3]:
                fail(f"{where}: hex does not match sRGB components")
            if len(declared) == 9 and declared_channels[3] != channels[3]:
                fail(f"{where}: hex alpha does not match alpha")
        return color_hex(channels)
    if kind in {"dimension", "duration"}:
        unit = "px" if kind == "dimension" else "ms"
        if not isinstance(value, dict) or value.get("unit") != unit or "value" not in value:
            fail(f"{where}: {kind} must have value and unit '{unit}'")
        result = number(value["value"], where, float_target=kind == "dimension")
        if kind == "duration":
            if result < 0 or result != int(result) or result > 9_223_372_036_854_775_807:
                fail(f"{where}: duration must be a nonnegative whole number of milliseconds fitting Long")
            return int(result)
        return result
    if kind == "number":
        return number(value, where, float_target=True)
    if kind == "fontWeight":
        result = number(value, where)
        if result != int(result) or not 1 <= result <= 1000:
            fail(f"{where}: fontWeight must be an integer from 1 to 1000")
        return int(result)
    if kind == "fontFamily":
        if not isinstance(value, list) or not value:
            fail(f"{where}: fontFamily must be a nonempty array of strings")
        if any(not isinstance(item, str) or not item.strip() or
               any(ord(character) < 32 for character in item) for item in value):
            fail(f"{where}: fontFamily contains an empty name or control character")
        return list(value)
    if kind == "cubicBezier":
        if not isinstance(value, list) or len(value) != 4:
            fail(f"{where}: cubicBezier must contain four numbers")
        for component in value:
            number(component, where, float_target=True)
        if not 0 <= value[0] <= 1 or not 0 <= value[2] <= 1:
            fail(f"{where}: cubicBezier x coordinates must be in [0, 1]")
        return list(value)
    fail(f"{where}: unsupported token type {kind!r}")


def collect_tokens(source: Any) -> dict[str, Token]:
    if not isinstance(source, dict):
        fail("Source root must be an object")
    tokens: dict[str, Token] = {}

    def visit(node: Any, path: tuple[str, ...]) -> None:
        where = ".".join(path) or "<root>"
        if not isinstance(node, dict):
            fail(f"{where}: groups and tokens must be objects")
        if "$type" in node and (not isinstance(node["$type"], str) or node["$type"] not in SUPPORTED_TYPES):
            fail(f"{where}: unsupported $type {node['$type']!r}")
        if "$value" in node:
            if not path:
                fail("Source root cannot itself be a token")
            kind = node.get("$type")
            if not isinstance(kind, str) or kind not in SUPPORTED_TYPES:
                fail(f"{where}: missing or unsupported explicit $type {kind!r}")
            if any(not key.startswith("$") for key in node):
                fail(f"{where}: a token cannot also contain child groups")
            tokens[where] = Token(path, kind, node["$value"])
            return
        if "$type" in node and not any(not key.startswith("$") for key in node):
            fail(f"{where}: typed token is missing $value")
        for key, child in node.items():
            if key.startswith("$"):
                continue
            if not key or any(character in key for character in ".{}"):
                fail(f"{where}: invalid token/group name {key!r}")
            visit(child, path + (key,))

    visit(source, ())
    if not tokens:
        fail("Source contains no tokens")
    return tokens


def resolve_tokens(tokens: dict[str, Token]) -> dict[str, Any]:
    resolved: dict[str, Any] = {}
    visiting: list[str] = []

    def resolve(name: str) -> Any:
        if name in resolved:
            return resolved[name]
        if name in visiting:
            fail("Alias cycle: " + " -> ".join(visiting[visiting.index(name):] + [name]))
        token = tokens[name]
        visiting.append(name)
        match = ALIAS.fullmatch(token.value) if isinstance(token.value, str) else None
        if match:
            target = match.group(1)
            if target not in tokens:
                fail(f"{name}: unknown alias {{{target}}}")
            if token.kind != tokens[target].kind:
                fail(f"{name}: {token.kind} alias targets {tokens[target].kind} token {target}")
            result = resolve(target)
        else:
            result = validate_value(token.kind, token.value, name)
        visiting.pop()
        resolved[name] = result
        return result

    for name in sorted(tokens):
        resolve(name)
    return resolved


def words(value: str) -> list[str]:
    value = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1-\2", value)
    value = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", value)
    if re.search(r"[^A-Za-z0-9_-]", value):
        fail(f"Token name cannot be exported to CSS/Kotlin: {value!r}")
    result = [part for part in re.split(r"[-_]+", value) if part]
    if not result:
        fail(f"Token name cannot be empty after normalization: {value!r}")
    return result


def css_name(path: tuple[str, ...]) -> str:
    return "--tp-" + "-".join(part.lower() for key in path for part in words(key))


def kotlin_name(value: str) -> str:
    name = "".join(part[:1].upper() + part[1:].lower() for part in words(value))
    return "N" + name if name[0].isdigit() else name


def validate_export_names(tokens: dict[str, Token]) -> None:
    css_names: dict[str, str] = {}
    kotlin_names: dict[tuple[str, ...], tuple[str, ...]] = {}
    for token in tokens.values():
        name = css_name(token.path)
        if name in css_names:
            fail(f"CSS name collision: {token.name} and {css_names[name]} both export {name}")
        css_names[name] = token.name
        for size in range(1, len(token.path) + 1):
            original = token.path[:size]
            normalized = tuple(kotlin_name(part) for part in original)
            previous = kotlin_names.get(normalized)
            if previous is not None and previous != original:
                fail(f"Kotlin name collision: {'.'.join(previous)} and {'.'.join(original)}")
            kotlin_names[normalized] = original


def numeric_text(value: int | float) -> str:
    return str(value) if isinstance(value, int) else format(value, ".12g")


def css_value(token: Token, value: Any) -> str:
    if token.kind == "dimension":
        typography = any(key.lower() == "typography" for key in token.path)
        if typography or token.path[-1] in {"fontSize", "letterSpacing"}:
            return numeric_text(value / 16) + "rem"
        return numeric_text(value) + "px"
    if token.kind == "duration":
        return numeric_text(value) + "ms"
    if token.kind == "fontFamily":
        generic = {"serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui", "ui-serif", "ui-sans-serif", "ui-monospace", "ui-rounded"}
        return ", ".join(item if item in generic else json.dumps(item, ensure_ascii=False) for item in value)
    if token.kind == "cubicBezier":
        return "cubic-bezier(" + ", ".join(numeric_text(item) for item in value) + ")"
    return value if token.kind == "color" else numeric_text(value)


def tree_for(tokens: dict[str, Token], resolved: dict[str, Any]) -> dict[str, Any]:
    root: dict[str, Any] = {}
    for name in sorted(tokens):
        token = tokens[name]
        node = root
        for part in token.path[:-1]:
            node = node.setdefault(part, {})
        node[token.path[-1]] = resolved[name]
    return root


def kotlin_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def kotlin_value(token: Token, value: Any) -> tuple[str, str, str]:
    if token.kind == "color":
        red, green, blue, alpha = color_channels(value)
        return "const val", "Int", f"0x{alpha:02X}{red:02X}{green:02X}{blue:02X}.toInt()"
    if token.kind == "fontFamily":
        return "val", "List<String>", "listOf(" + ", ".join(kotlin_string(item) for item in value) + ")"
    if token.kind == "cubicBezier":
        return "val", "List<Float>", "listOf(" + ", ".join(numeric_text(item) + "f" for item in value) + ")"
    if token.kind == "duration":
        return "const val", "Long", f"{value}L"
    if token.kind == "fontWeight":
        return "const val", "Int", str(value)
    return "const val", "Float", numeric_text(value) + "f"


def render_kotlin(tokens: dict[str, Token], resolved: dict[str, Any]) -> str:
    groups: dict[str, Any] = {}
    for name in sorted(tokens):
        node = groups
        for part in tokens[name].path[:-1]:
            node = node.setdefault(part, {})
        node[tokens[name].path[-1]] = tokens[name]
    lines = [
        "// Generated by scripts/generate-design-tokens.py. Do not edit.",
        "// Integration template only; dimensions retain source pixel values.",
        "package com.hamhuo.tplanner.designsystem", "", "object TPlannerLightTokens {",
    ]

    def emit(node: dict[str, Any], depth: int) -> None:
        indent = "    " * depth
        for name, child in node.items():
            if isinstance(child, Token):
                declaration, kind, value = kotlin_value(child, resolved[child.name])
                lines.append(f"{indent}{declaration} {kotlin_name(name)}: {kind} = {value}")
            else:
                lines.append(f"{indent}object {kotlin_name(name)} {{")
                emit(child, depth + 1)
                lines.append(f"{indent}}}")

    emit(groups, 1)
    return "\n".join(lines + ["}", ""])


def luminance(rgb: list[float]) -> float:
    linear = [component / 12.92 if component <= 0.04045 else
              ((component + 0.055) / 1.055) ** 2.4 for component in rgb]
    return sum(component * weight for component, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


def contrast_report(source: dict[str, Any], tokens: dict[str, Token], resolved: dict[str, Any]) -> dict[str, Any]:
    extensions = source.get("$extensions", {})
    package = extensions.get("com.tplanner", {}) if isinstance(extensions, dict) else {}
    pairs = package.get("contrastPairs") if isinstance(package, dict) else None
    if not isinstance(pairs, list) or not pairs:
        fail("$extensions.com.tplanner.contrastPairs must be a nonempty array")
    results = []
    for index, pair in enumerate(pairs):
        where = f"contrastPairs[{index}]"
        if not isinstance(pair, dict):
            fail(f"{where}: expected an object")
        for role in ("foreground", "background"):
            name = pair.get(role)
            if not isinstance(name, str) or name not in tokens or tokens[name].kind != "color":
                fail(f"{where}.{role}: expected an existing color token path")
        threshold = number(pair.get("minimum"), f"{where}.minimum")
        if not 1 <= threshold <= 21:
            fail(f"{where}: contrast minimum must be from 1 to 21")
        if not isinstance(pair.get("required"), bool):
            fail(f"{where}.required: expected true or false")
        if not isinstance(pair.get("purpose"), str) or not pair["purpose"].strip():
            fail(f"{where}.purpose: expected a nonempty description")
        foreground = resolved[pair["foreground"]]
        background = resolved[pair["background"]]
        fg, bg = color_channels(foreground), color_channels(background)
        if bg[3] != 255:
            fail(f"{where}: background must be opaque; provide its final composited color")
        opacity = fg[3] / 255
        composed = [(fg[channel] * opacity + bg[channel] * (1 - opacity)) / 255 for channel in range(3)]
        fg_luminance = luminance(composed)
        bg_luminance = luminance([channel / 255 for channel in bg[:3]])
        ratio = (max(fg_luminance, bg_luminance) + 0.05) / (min(fg_luminance, bg_luminance) + 0.05)
        results.append({
            "foreground": pair["foreground"], "background": pair["background"],
            "foregroundHex": foreground, "backgroundHex": background,
            "compositedForegroundHex": color_hex([byte(channel) for channel in composed] + [255]),
            "minimum": threshold, "ratio": ratio, "pass": ratio >= threshold,
            "required": pair["required"], "purpose": pair["purpose"],
        })
    required = [pair for pair in results if pair["required"]]
    return {
        "theme": "tplanner-light",
        "method": "WCAG 2 sRGB luminance; exported 8-bit colors; foreground alpha composited over opaque background",
        "requiredPass": all(pair["pass"] for pair in required),
        "summary": {"pairs": len(results), "required": len(required),
                    "requiredFailures": sum(not pair["pass"] for pair in required),
                    "diagnosticFailures": sum(not pair["pass"] and not pair["required"] for pair in results)},
        "pairs": results,
    }


def render_outputs(tokens: dict[str, Token], resolved: dict[str, Any], report: dict[str, Any]) -> dict[str, str]:
    css = ["/* Generated by scripts/generate-design-tokens.py. Do not edit. */",
           ':root[data-tp-theme="light"], [data-tp-theme="light"] {', "  color-scheme: light;"]
    css.extend(f"  {css_name(tokens[name].path)}: {css_value(tokens[name], resolved[name])};" for name in sorted(tokens))
    css.extend(["}", ""])
    tree = json.dumps(tree_for(tokens, resolved), ensure_ascii=False, indent=2, allow_nan=False)
    return {
        "tplanner-light.css": "\n".join(css),
        "tplanner-light.ts": "// Generated by scripts/generate-design-tokens.py. Do not edit.\nexport const lightTokens = " + tree + " as const;\n",
        "tplanner-light.mjs": "// Generated by scripts/generate-design-tokens.py. Do not edit.\nexport const lightTokens = " + tree + ";\n",
        "TPlannerLightTokens.kt": render_kotlin(tokens, resolved),
        "contrast-report.json": json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
    }


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="Fail if any generated file is missing or stale")
    args = parser.parse_args()
    try:
        source = json.loads(args.source.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object,
                            parse_constant=lambda value: fail(f"Non-finite JSON number: {value}"))
        tokens = collect_tokens(source)
        resolved = resolve_tokens(tokens)
        validate_export_names(tokens)
        report = contrast_report(source, tokens, resolved)
        if not report["requiredPass"]:
            failures = [f"{pair['foreground']} on {pair['background']} ({pair['ratio']:.4f} < {pair['minimum']})"
                        for pair in report["pairs"] if pair["required"] and not pair["pass"]]
            fail("Required contrast failures; no exports written: " + "; ".join(failures))
        outputs = render_outputs(tokens, resolved, report)
        if args.check:
            stale = [name for name, content in outputs.items()
                     if not (args.output / name).is_file() or
                     (args.output / name).read_bytes() != content.encode("utf-8")]
            if stale:
                fail("Missing or stale exports: " + ", ".join(stale))
        else:
            args.output.mkdir(parents=True, exist_ok=True)
            for name, content in outputs.items():
                (args.output / name).write_bytes(content.encode("utf-8"))
        summary = report["summary"]
        action = "Checked" if args.check else "Generated"
        print(f"{action} {len(outputs)} exports from {len(tokens)} tokens; "
              f"{summary['required']} required contrast pairs passed; "
              f"{summary['diagnosticFailures']} diagnostic contrast failures.")
        return 0
    except (OSError, ValueError, RecursionError) as error:
        print(f"Token generation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
