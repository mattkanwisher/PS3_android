#!/usr/bin/env python3
"""Print the center 'x y' of the first uiautomator-dump node whose text or
content-desc contains the query (case-insensitive). Exit 1 when absent."""

import re
import sys
import xml.etree.ElementTree as ET


def main(path, query):
    q = query.lower()
    for node in ET.parse(path).iter("node"):
        hay = (node.get("text", "") + " " + node.get("content-desc", "")).lower()
        if q in hay:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                print((x1 + x2) // 2, (y1 + y2) // 2)
                return 0
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
