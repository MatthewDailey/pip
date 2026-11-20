import os
import sys
from lib import write, OUT
from gen_a import BUILDERS_A
from gen_b import BUILDERS_B

only = sys.argv[1:]
for name, fn in {**BUILDERS_A, **BUILDERS_B}.items():
    if only and name not in only:
        continue
    svg, motion = fn()
    motion["svg"] = f"{name}.svg"
    motion = {"piece": motion.pop("piece"), "svg": motion.pop("svg"), **motion}
    write(name, svg, motion)
    print(f"{name:24s} svg {os.path.getsize(f'{OUT}/{name}.svg') / 1024:7.1f} KB   json {os.path.getsize(f'{OUT}/{name}.json') / 1024:7.1f} KB")
