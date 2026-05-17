#!/usr/bin/env python3
"""Minimal DJI WPML KMZ validator.

Walks a KMZ archive and checks the required wpml:* elements are present
in both wpmz/template.kml and wpmz/waylines.wpml, per the WPML v1.0.x
spec at https://github.com/dji-sdk/Cloud-API-Doc/tree/master/docs/cn/60.api-reference/00.dji-wpml.

Exits 0 if valid; non-zero on first failure. Intended for smoke testing
hand-edited or generated KMZ files before adb-push to a real RC.

Usage: python3 validate_kmz.py <path-to-kmz>
"""
import sys
import zipfile
from xml.etree import ElementTree as ET

NS = {"kml": "http://www.opengis.net/kml/2.2",
      "wpml": "http://www.dji.com/wpmz/1.0.6"}

# Required <wpml:missionConfig> children (both files)
MISSION_CONFIG_REQUIRED = [
    "flyToWaylineMode",
    "finishAction",
    "exitOnRCLost",
    "takeOffSecurityHeight",
    "globalTransitionalSpeed",
    "droneInfo",
    "payloadInfo",
]

FINISH_ACTION_VALUES = {"goHome", "noAction", "autoLand", "gotoFirstWaypoint"}
EXIT_ON_RC_LOST_VALUES = {"goContinue", "executeLostAction"}
EXECUTE_RC_LOST_ACTION_VALUES = {"goBack", "landing", "hover"}
FLY_TO_WAYLINE_MODE_VALUES = {"safely", "pointToPoint"}


class WpmlError(Exception):
    pass


def must(node, tag, where):
    found = node.find(f"wpml:{tag}", NS)
    if found is None:
        raise WpmlError(f"{where}: missing <wpml:{tag}>")
    return found


def must_text(node, tag, where):
    el = must(node, tag, where)
    if el.text is None or not el.text.strip():
        raise WpmlError(f"{where}: <wpml:{tag}> is empty")
    return el.text.strip()


def must_enum(value, allowed, where, tag):
    if value not in allowed:
        raise WpmlError(f"{where}: <wpml:{tag}> = '{value}' not in {sorted(allowed)}")


def check_mission_config(doc, where):
    cfg = must(doc, "missionConfig", where)
    for tag in MISSION_CONFIG_REQUIRED:
        must(cfg, tag, f"{where}/missionConfig")

    must_enum(must_text(cfg, "flyToWaylineMode", where),
              FLY_TO_WAYLINE_MODE_VALUES, where, "flyToWaylineMode")
    must_enum(must_text(cfg, "finishAction", where),
              FINISH_ACTION_VALUES, where, "finishAction")
    exit_on_rc_lost = must_text(cfg, "exitOnRCLost", where)
    must_enum(exit_on_rc_lost, EXIT_ON_RC_LOST_VALUES, where, "exitOnRCLost")
    if exit_on_rc_lost == "executeLostAction":
        action_el = cfg.find("wpml:executeRCLostAction", NS)
        if action_el is None or action_el.text is None:
            raise WpmlError(f"{where}: exitOnRCLost=executeLostAction but "
                            "<wpml:executeRCLostAction> missing")
        must_enum(action_el.text.strip(),
                  EXECUTE_RC_LOST_ACTION_VALUES, where, "executeRCLostAction")

    drone = must(cfg, "droneInfo", where)
    drone_enum = int(must_text(drone, "droneEnumValue", f"{where}/droneInfo"))
    if drone_enum == 67:
        must(drone, "droneSubEnumValue", f"{where}/droneInfo")

    payload = must(cfg, "payloadInfo", where)
    must(payload, "payloadEnumValue", f"{where}/payloadInfo")
    must(payload, "payloadPositionIndex", f"{where}/payloadInfo")


def check_template_kml(root):
    doc = root.find("kml:Document", NS)
    if doc is None:
        raise WpmlError("template.kml: no <Document>")

    check_mission_config(doc, "template.kml")

    folder = doc.find("kml:Folder", NS)
    if folder is None:
        raise WpmlError("template.kml: no <Folder>")

    must_enum(must_text(folder, "templateType", "template.kml/Folder"),
              {"waypoint", "mapping2d", "mapping3d", "strip"},
              "template.kml/Folder", "templateType")
    must(folder, "templateId", "template.kml/Folder")
    coord = must(folder, "waylineCoordinateSysParam", "template.kml/Folder")
    must(coord, "coordinateMode", "template.kml/Folder/waylineCoordinateSysParam")
    must(coord, "heightMode", "template.kml/Folder/waylineCoordinateSysParam")
    must(folder, "autoFlightSpeed", "template.kml/Folder")

    placemarks = folder.findall("kml:Placemark", NS)
    if not placemarks:
        raise WpmlError("template.kml: no <Placemark>")
    for i, p in enumerate(placemarks):
        where = f"template.kml/Placemark[{i}]"
        pt = p.find("kml:Point/kml:coordinates", NS)
        if pt is None or not pt.text or "," not in pt.text:
            raise WpmlError(f"{where}: missing or malformed <coordinates>")
        must(p, "index", where)
        must(p, "ellipsoidHeight", where)
        must(p, "height", where)


def check_waylines_wpml(root):
    doc = root.find("kml:Document", NS)
    if doc is None:
        raise WpmlError("waylines.wpml: no <Document>")

    check_mission_config(doc, "waylines.wpml")

    folders = doc.findall("kml:Folder", NS)
    if not folders:
        raise WpmlError("waylines.wpml: no <Folder>")
    for fi, folder in enumerate(folders):
        where = f"waylines.wpml/Folder[{fi}]"
        must(folder, "templateId", where)
        must(folder, "executeHeightMode", where)
        must(folder, "waylineId", where)
        must(folder, "autoFlightSpeed", where)

        placemarks = folder.findall("kml:Placemark", NS)
        if not placemarks:
            raise WpmlError(f"{where}: no <Placemark>")
        for pi, p in enumerate(placemarks):
            pwhere = f"{where}/Placemark[{pi}]"
            pt = p.find("kml:Point/kml:coordinates", NS)
            if pt is None or not pt.text or "," not in pt.text:
                raise WpmlError(f"{pwhere}: missing or malformed <coordinates>")
            must(p, "index", pwhere)
            must(p, "executeHeight", pwhere)
            must(p, "waypointSpeed", pwhere)


def main(path):
    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        for required in ("wpmz/template.kml", "wpmz/waylines.wpml"):
            if required not in names:
                raise WpmlError(f"{path}: missing entry {required}")
        template = ET.fromstring(z.read("wpmz/template.kml"))
        waylines = ET.fromstring(z.read("wpmz/waylines.wpml"))

    check_template_kml(template)
    check_waylines_wpml(waylines)
    print(f"{path}: OK")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: validate_kmz.py <path-to-kmz>", file=sys.stderr)
        sys.exit(2)
    try:
        main(sys.argv[1])
    except WpmlError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
    except (zipfile.BadZipFile, ET.ParseError) as e:
        print(f"FAIL: parse error: {e}", file=sys.stderr)
        sys.exit(1)
