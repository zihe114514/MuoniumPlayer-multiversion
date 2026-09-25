# -*- coding: utf-8 -*-
import re, xml.etree.ElementTree as ET

SVG="http://www.w3.org/2000/svg"
XLINK="http://www.w3.org/1999/xlink"
ET.register_namespace("", SVG)
ET.register_namespace("xlink", XLINK)

SRC=r"C:\MUSIC\LOg_src.svg"
OUT=r"C:\MUSIC\LOg_zh.svg"

# target position -> chinese text.  positions are the group's OWN transform translate.
targets = [
    dict(x=469,     y=366,     zh="\u5df2\u77e5\u987e\u5ba2"),                 # KNOWN SHOPPER
    dict(x=469,     y=398,     zh="\u53ef\u4fe1\u7f51\u7edc"),                 # TRUSTED NETWORK
    dict(x=469,     y=430,     zh="\u7b56\u7565\u5339\u914d"),                 # POLICY MATCH
    dict(x=24,      y=35,      zh="\u900f\u660e\u4e0e\u53ef\u63a7"),           # Transparency and control
    dict(x=433,     y=337.25,  zh="\u5173\u952e\u539f\u56e0"),                 # KEY REASONS
    dict(x=433,     y=257.25,  zh="\u51b3\u7b56\u7528\u65f6"),                 # DECISION IN
    dict(x=433.483, y=177.25,  zh="\u7f6e\u4fe1\u5ea6"),                       # CONFIDENCE
    dict(x=484.222, y=119.835, zh="\u5f85\u5904\u7406"),                       # Pending
    dict(x=438.352, y=119.927, zh="\u5df2\u6279\u51c6"),                       # Approved
    dict(x=127.5,   y=572.25,  zh="\u51b3\u7b56\u5b8c\u6210\uff1a\u5df2\u6279\u51c6"),  # Decision made: Approved
    dict(x=127.5,   y=546.25,  zh="\u5df2\u6536\u96c6\u5e76\u8bc4\u4f30\u8bc1\u636e"),  # Evidence collected and evaluated
    dict(x=127.5,   y=520.25,  zh="\u5df2\u6536\u5230\u8bf7\u6c42"),           # Request received
    dict(x=17.497,  y=491.227, zh="\u51b3\u7b56\u65e5\u5fd7"),                 # DECISION LOG
    dict(x=221.622, y=423.055, zh="\u00b7 \u8bc4\u5206 > 90"),                 # . score > 90
    dict(x=147.623, y=424.296, zh="\u56de\u8bbf"),                            # Returning
    dict(x=179.864, y=398.22,  zh="\u8eab\u4efd\u9a8c\u8bc1 2"),               # AUTHENTICATION 2
    dict(x=206.297, y=327.062, zh="\u00b7 \u4f4e\u6469\u64e6"),               # . low friction
    dict(x=140.585, y=328.345, zh="\u8c41\u514d"),                            # Exempt
    dict(x=179.864, y=302.22,  zh="\u8eab\u4efd\u9a8c\u8bc1"),                 # AUTHENTICATION
    dict(x=255.854, y=232.275, zh="\u00b7 \u53ef\u4fe1\u6863\u6848"),         # . trusted profile
    dict(x=134.293, y=231.062, zh="\u8bc4\u5206"),                            # Score
    dict(x=153.31,  y=206.22,  zh="\u7f51\u7edc"),                            # NETWORK
    dict(x=200,     y=139.75,  zh="\u00b7 \u65e0\u6b3a\u8bc8\u8bb0\u5f55"),   # . no fraud history
    dict(x=116.5,   y=139.75,  zh="\u51fa\u73b0"),                            # Seen
    dict(x=116.5,   y=115.25,  zh="\u987e\u5ba2"),                            # SHOPPER
]
for t in targets:
    t["done"]=False

def get_translate(transform):
    if not transform: return None
    m=re.search(r"matrix\(([^)]*)\)", transform)
    if m:
        p=[float(v) for v in re.split(r"[ ,]+", m.group(1).strip())]
        if len(p)==6: return (p[4], p[5])
    m=re.search(r"translate\(([^)]*)\)", transform)
    if m:
        p=[float(v) for v in re.split(r"[ ,]+", m.group(1).strip())]
        if len(p)>=2: return (p[0], p[1])
    return None

NUM=re.compile(r"-?\d+\.?\d*(?:e-?\d+)?")

def path_extent(g):
    xs=[]; ys=[]; fill=None
    for pth in g.iter("{%s}path"%SVG):
        d=pth.get("d") or ""
        if not d.strip(): continue
        nums=[float(n) for n in NUM.findall(d)]
        xs+=nums[0::2]; ys+=nums[1::2]
        f=pth.get("fill")
        if fill is None and f and f!="none": fill=f
    if not xs: return None
    return dict(minx=min(xs), miny=min(ys), maxy=max(ys), fill=fill)

def find_match(g):
    tr=get_translate(g.get("transform"))
    if not tr: return None
    for t in targets:
        if t["done"]: continue
        if abs(tr[0]-t["x"])<0.6 and abs(tr[1]-t["y"])<0.6:
            return t
    return None

tree=ET.parse(SRC)
root=tree.getroot()

matched=0
for g in root.iter("{%s}g"%SVG):
    t=find_match(g)
    if not t: continue
    ext=path_extent(g)
    if not ext:  # group has no glyph paths at this level; skip (likely wrong group)
        continue
    cap = -ext["miny"] if ext["miny"]<0 else (ext["maxy"]-ext["miny"])
    if cap<=0: cap=(ext["maxy"]-ext["miny"]) or 10
    fs = round(cap/0.80, 2)
    fill = ext["fill"] or "#07143c"
    # clear existing children
    for c in list(g):
        g.remove(c)
    txt=ET.SubElement(g, "{%s}text"%SVG)
    txt.set("x", str(round(ext["minx"],2)))
    txt.set("y", "0")
    txt.set("fill", fill)
    txt.set("font-size", str(fs))
    txt.set("font-family", "'Microsoft YaHei','\u5fae\u8f6f\u96c5\u9ed1','PingFang SC','Noto Sans SC',sans-serif")
    txt.set("font-weight", "600")
    txt.text=t["zh"]
    t["done"]=True
    matched+=1
    print(f"OK  ({t['x']},{t['y']}) fs={fs} fill={fill} minx={round(ext['minx'],1)} -> {t['zh']}")

for t in targets:
    if not t["done"]:
        print(f"MISS ({t['x']},{t['y']}) -> {t['zh']}")

tree.write(OUT, encoding="utf-8", xml_declaration=True)
print("matched", matched, "of", len(targets))
