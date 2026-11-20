#!/usr/bin/env python3
"""Extracts the .riv object schema from a rive-runtime checkout.

The runtime commit pinned by rive-android no longer ships dev/defs/*.json, so the
generated C++ (what the reader actually deserializes) is the source of truth:

- include/rive/generated/**/*_base.hpp gives each class, its parents, its type key
  and its property keys.
- include/rive/generated/core_registry.hpp gives each property's field type
  (propertyFieldId and isCallback), which is what File::readObjects uses to skip
  or decode a property.
- include/rive/file.hpp gives the format version.

Usage: extract_schema.py <rive-runtime checkout> [pinnedBy] > schema.json
Re-run it whenever the pinned runtime moves, and commit the JSON with the code.
"""
import json, os, re, subprocess, sys

runtime = sys.argv[1]
pinned_by = sys.argv[2] if len(sys.argv) > 2 else "rive-android 11.16.3"
gen = os.path.join(runtime, "include", "rive", "generated")


def read(path):
    with open(path) as f:
        return f.read()


# Field types from the registry. propertyFieldId groups `case XBase::yPropertyKey:`
# lines under a `return CoreZType::id;`.
registry = read(os.path.join(gen, "core_registry.hpp"))


def block(name):
    m = re.search(r"static \w+ " + name + r"\(.*?\n  \}\n", registry, re.S)
    if not m:
        sys.exit("core_registry.hpp: no " + name)
    return m.group(0)


field_types = {}  # (class, prop) -> type
pending = []
for line in block("propertyFieldId").splitlines():
    case = re.search(r"case (\w+)Base::(\w+)PropertyKey:", line)
    if case:
        pending.append((case.group(1), case.group(2)))
        continue
    ret = re.search(r"return Core(\w+)Type::id;", line)
    if ret:
        for p in pending:
            field_types[p] = ret.group(1)
        pending = []
for cls, prop in re.findall(r"case (\w+)Base::(\w+)PropertyKey:", block("isCallback")):
    field_types[(cls, prop)] = "Callback"

classes = {}
for dirpath, _, files in os.walk(gen):
    for f in sorted(files):
        if not f.endswith("_base.hpp"):
            continue
        src = read(os.path.join(dirpath, f))
        # Declarations can span lines and inherit from more than one base
        # (SolidColor : Component, ColorChannels).
        m = re.search(r"class (\w+)Base\s*:\s*((?:public\s+\w+\s*,?\s*)+)\{", src, re.S)
        if not m:
            m = re.search(r"class (\w+)Base\s*\{", src)
            if not m:
                continue
            parents = []
        else:
            parents = [p.replace("Base", "") if p.endswith("Base") else p
                       for p in re.findall(r"public\s+(\w+)", m.group(2))]
        name = m.group(1)
        tk = re.search(r"static const uint16_t typeKey = (\d+);", src)
        props = {}
        for pname, key in re.findall(r"static const uint16_t (\w+)PropertyKey = (\d+);", src):
            ftype = field_types.get((name, pname))
            if ftype is None:
                # Not in the registry (editor-only or new): fall back to the header's deserialize case.
                d = re.search(r"case " + pname + r"PropertyKey:\s*\n(?:#.*\n|\s*\S.*\n)*?\s*\S.*?Core(\w+)Type::", src)
                ftype = d.group(1) if d else None
            props[pname] = {"key": int(key), "type": ftype}
        classes[name] = {
            "typeKey": int(tk.group(1)) if tk else None,
            "parents": [p for p in parents if p != "Core"],
            "file": os.path.relpath(os.path.join(dirpath, f), gen),
            "props": props,
        }

file_hpp = read(os.path.join(runtime, "include", "rive", "file.hpp"))
major = int(re.search(r"majorVersion = (\d+);", file_hpp).group(1))
minor = int(re.search(r"minorVersion = (\d+);", file_hpp).group(1))
try:
    commit = subprocess.check_output(["git", "-C", runtime, "rev-parse", "HEAD"], text=True).strip()
except Exception:
    commit = None

json.dump({
    "runtime": {
        "repo": "rive-app/rive-runtime",
        "commit": commit,
        "pinnedBy": pinned_by,
        "formatMajor": major,
        "formatMinor": minor,
    },
    "classes": classes,
}, sys.stdout, indent=1, sort_keys=True)
sys.stdout.write("\n")
