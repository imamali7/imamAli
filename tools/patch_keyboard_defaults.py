#!/usr/bin/env python3
"""Set the requested portrait keyboard defaults without changing landscape preferences."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
path = root / "res/xml/prefs.xml"
if not path.is_file():
    raise SystemExit("Missing res/xml/prefs.xml")

android = "{http://schemas.android.com/apk/res/android}"
tree = ET.parse(str(path))
matches = []
for node in tree.iter():
    if node.attrib.get(android + "key1") == "@string/key_hight_vertical":
        matches.append(node)

if len(matches) != 1:
    raise SystemExit("Expected exactly one portrait keyboard layout preference; found " + str(len(matches)))

node = matches[0]
expected = {
    "key1": "@string/key_hight_vertical",
    "key2": "@string/row_gap_vertical",
    "key3": "@string/text_size_vertical",
    "key4": "@string/bottom_padding_vertical",
    "key5": "@string/arrows_keys_hight_vertical",
}
for suffix, key in expected.items():
    if node.attrib.get(android + suffix) != key:
        raise SystemExit("Portrait preference mapping mismatch for " + suffix)

defaults = {
    "defaultValue1": "138",
    "defaultValue2": "169",
    "defaultValue3": "142",
    "defaultValue4": "20",
    "defaultValue5": "58",
}
for attribute, value in defaults.items():
    node.set(android + attribute, value)

# Expose an explicit, enabled-by-default clipboard-history switch in keyboard settings.
layout_screens = [n for n in tree.iter() if n.attrib.get(android + "key") == "layout_menu"]
if len(layout_screens) != 1:
    raise SystemExit("Expected exactly one layout settings screen")
if not any(n.attrib.get(android + "key") == "clipboard_history_enabled" for n in layout_screens[0].iter()):
    toggle = ET.Element("CheckBoxPreference")
    toggle.set(android + "persistent", "true")
    toggle.set(android + "title", "@string/c6")
    toggle.set(android + "key", "clipboard_history_enabled")
    toggle.set(android + "defaultValue", "true")
    layout_screens[0].append(toggle)

tree.write(str(path), encoding="utf-8", xml_declaration=True)
verified = ET.parse(str(path))
portrait = [n for n in verified.iter() if n.attrib.get(android + "key1") == "@string/key_hight_vertical"]
if len(portrait) != 1 or any(portrait[0].attrib.get(android + k) != v for k, v in defaults.items()):
    raise SystemExit("Portrait keyboard defaults failed post-write validation")
print("OK: portrait defaults = key height 138, row gap 169, text size 142, bottom padding 20, arrow height 58")
