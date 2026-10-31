#!/usr/bin/env python3
"""Verify SSDP -> description -> Browse -> HEAD -> bounded Range reads. Python stdlib only."""
import argparse
import collections
import socket
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

SOAP = "http://schemas.xmlsoap.org/soap/envelope/"
NS = {"d": "urn:schemas-upnp-org:device-1-0", "didl": "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"}


def fetch(url, method="GET", headers=None, body=None, limit=4 * 1024 * 1024):
    request = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    with urllib.request.urlopen(request, timeout=12) as response:
        data = response.read(limit + 1)
        if len(data) > limit:
            raise RuntimeError(f"Response exceeded {limit} bytes: {url}")
        return response.status, response.headers, data


def discover(interface):
    locations = set()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as sock:
        sock.settimeout(0.4)
        sock.bind((interface or "0.0.0.0", 0))
        if interface:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(interface))
        for target in ("urn:schemas-upnp-org:device:MediaServer:1", "ssdp:all"):
            message = f'M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: {target}\r\n\r\n'
            sock.sendto(message.encode("ascii"), ("239.255.255.250", 1900))
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                packet, _ = sock.recvfrom(8192)
                headers = dict(line.split(":", 1) for line in packet.decode("ascii", errors="replace").splitlines()[1:] if ":" in line)
                headers = {key.lower(): value.strip() for key, value in headers.items()}
                if "location" in headers:
                    locations.add(headers["location"])
            except socket.timeout:
                pass
    return sorted(locations)


def action(url, service, name, args):
    envelope = ET.Element(f"{{{SOAP}}}Envelope")
    body = ET.SubElement(envelope, f"{{{SOAP}}}Body")
    node = ET.SubElement(body, f"{{{service}}}{name}")
    for key, value in args.items():
        ET.SubElement(node, key).text = str(value)
    status, _, data = fetch(url, "POST", {"Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": f'"{service}#{name}"'}, ET.tostring(envelope, encoding="utf-8", xml_declaration=True))
    assert status == 200, status
    return ET.fromstring(data)


def verify(location):
    status, _, data = fetch(location)
    assert status == 200, status
    root = ET.fromstring(data)
    name = root.findtext("d:device/d:friendlyName", namespaces=NS)
    if name != "ZLNA":
        print(f"SKIP {name or 'unknown device'}: {location}")
        return False
    print(f"PASS description: {name} at {location}")
    directory = None
    for service in root.findall("d:device/d:serviceList/d:service", NS):
        kind = service.findtext("d:serviceType", namespaces=NS)
        schema = urllib.parse.urljoin(location, service.findtext("d:SCPDURL", namespaces=NS))
        ET.fromstring(fetch(schema)[2])
        if kind.endswith(":ContentDirectory:1"):
            directory = (urllib.parse.urljoin(location, service.findtext("d:controlURL", namespaces=NS)), kind)
    assert directory, "No ContentDirectory"
    print("PASS service schemas")
    queue = collections.deque(["0"])
    visited = set()
    resource = None
    while queue and resource is None:
        object_id = queue.popleft()
        if object_id in visited:
            continue
        visited.add(object_id)
        start = 0
        while True:
            result = action(*directory, "Browse", {"ObjectID": object_id, "BrowseFlag": "BrowseDirectChildren", "Filter": "*", "StartingIndex": start, "RequestedCount": 100, "SortCriteria": ""})
            didl = ET.fromstring(result.findtext(".//Result"))
            for container in didl.findall("didl:container", NS):
                queue.append(container.attrib["id"])
            # A real DCIM folder can list an empty recording before playable videos.
            resource = next((r for r in didl.findall("didl:item/didl:res", NS)
                             if int(r.attrib["size"]) > 0), None)
            if resource is not None:
                break
            returned = int(result.findtext(".//NumberReturned"))
            start += returned
            if returned == 0 or start >= int(result.findtext(".//TotalMatches")):
                break
    if resource is None:
        raise RuntimeError("Directory is valid but contains no nonempty video to test")
    url = resource.text
    print("PASS Browse and DIDL-Lite video resource")
    status, headers, data = fetch(url, "HEAD")
    assert status == 200 and not data
    size = int(headers["Content-Length"])
    assert size == int(resource.attrib["size"])
    assert headers.get("Accept-Ranges") == "bytes"
    print(f"PASS HEAD: {size:,} bytes, {headers.get('Content-Type')}")
    for start in sorted({0, size // 2, max(0, size - 65536)}):
        end = min(size - 1, start + 65535)
        status, headers, data = fetch(url, headers={"Range": f"bytes={start}-{end}"}, limit=65536)
        assert status == 206, f"Expected 206, received {status}"
        assert headers["Content-Range"] == f"bytes {start}-{end}/{size}"
        assert len(data) == end - start + 1
        print(f"PASS Range {start}-{end}: {len(data)} bytes")
    print("Protocol checks passed. Quest/SKYBOX codec, sustained throughput and actual seeking still require a headset test.")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", help="Device description URL from ZLNA diagnostics; otherwise use SSDP")
    parser.add_argument("--interface", help="Mac Wi-Fi IPv4 address for SSDP multicast")
    args = parser.parse_args()
    locations = [args.url] if args.url else discover(args.interface)
    if not locations:
        raise RuntimeError("No SSDP servers found; connect this computer and the phone to the same Wi-Fi (or connect to the phone hotspot), then run ZLNA")
    success = False
    for location in locations:
        try:
            success |= verify(location)
        except Exception as error:
            print(f"FAIL {location}: {error}", file=sys.stderr)
    return 0 if success else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
