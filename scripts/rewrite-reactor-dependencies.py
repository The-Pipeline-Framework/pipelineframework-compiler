#!/usr/bin/env python3
"""Align literal dependency versions that point at projects in this Maven reactor."""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

root_dir = pathlib.Path(sys.argv[1]).resolve()
candidate_version = sys.argv[2]
namespace = "{http://maven.apache.org/POM/4.0.0}"
poms = [root_dir / "pom.xml", *sorted(root_dir.glob("*/pom.xml"))]
reactor = set()


def child_text(element: ET.Element, name: str) -> str | None:
    return element.findtext(namespace + name) or element.findtext(name)


for pom in poms:
    project = ET.parse(pom).getroot()
    group = child_text(project, "groupId")
    if not group:
        parent = project.find(namespace + "parent") or project.find("parent")
        group = child_text(parent, "groupId") if parent is not None else None
    artifact = child_text(project, "artifactId")
    if group and artifact:
        reactor.add((group, artifact))

for pom in poms:
    source = pom.read_text()

    def update_dependency(match: re.Match[str]) -> str:
        block = match.group(0)
        group_match = re.search(r"<groupId>\s*([^<]+)\s*</groupId>", block)
        artifact_match = re.search(r"<artifactId>\s*([^<]+)\s*</artifactId>", block)
        if not group_match or not artifact_match or (group_match.group(1).strip(), artifact_match.group(1).strip()) not in reactor:
            return block
        return re.sub(r"(<version>\s*)[^<]+(\s*</version>)", rf"\g<1>{candidate_version}\2", block, count=1)

    updated = re.sub(r"<dependency\b[^>]*>.*?</dependency>", update_dependency, source, flags=re.DOTALL)
    if updated != source:
        pom.write_text(updated)
