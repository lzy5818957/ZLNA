#!/usr/bin/env python3
"""Exercise the in-app screen-off indicator on the dedicated ZLNA_API36 emulator."""
import argparse
import collections
import re
import socket
import subprocess
import time
import urllib.parse
import xml.etree.ElementTree as ET

from probe import action, fetch, NS

def run(adb, serial):
    command = [adb, "-s", serial]
    def call(*args):
        return subprocess.check_output(command + list(args), text=True, timeout=12)
    if not serial.startswith("emulator-") or call("emu", "avd", "name").splitlines()[0] != "ZLNA_API36":
        raise RuntimeError("This screen/foreground test only runs on the dedicated ZLNA_API36 emulator")
    token = str(time.time_ns())
    runner = subprocess.Popen(command + ["shell", "am", "instrument", "-w", "--no-window-animation",
        "-e", "class", "app.zlna.SetupAndCoverTest#externalReadAfterOneMinute", "-e", "screenProbe", "external",
        "-e", "probeToken", token, "app.zlna.test/androidx.test.runner.AndroidJUnitRunner"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    mapping = None
    try:
        end = time.monotonic() + 20
        while time.monotonic() < end:
            logs = call("logcat", "-d", "-s", "ZLNA-test:I")
            found = re.search(r"locked " + token + r" .*?http://[\d.]+:(\d+)/description\.xml", logs)
            if found: break
            if runner.poll() is not None: raise RuntimeError(runner.communicate()[0])
            time.sleep(.25)
        else: raise RuntimeError("No screen-off signal from test APK")
        locked = time.monotonic()
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        assert "OK" in call("emu", "redir", "add", f"tcp:{port}:{found[1]}")
        mapping = f"tcp:{port}"
        base = f"http://127.0.0.1:{port}"
        queue = collections.deque(["0"])
        while queue:
            args = dict(ObjectID=queue.popleft(), BrowseFlag="BrowseDirectChildren", Filter="*", StartingIndex=0, RequestedCount=0, SortCriteria="")
            soap = action(base+"/control/content", "urn:schemas-upnp-org:service:ContentDirectory:1", "Browse", args)
            didl = ET.fromstring(soap.findtext(".//Result"))
            queue.extend(node.attrib["id"] for node in didl.findall("didl:container", NS))
            item = next((n for n in didl.findall("didl:item/didl:res",NS) if int(n.attrib["size"]) >= 32),None)
            if item is not None: break
        else: raise RuntimeError("Choose the emulator Movies fixture with a nonempty video first")
        url = base + urllib.parse.urlsplit(item.text).path
        def read_video():
            status, headers, data = fetch(url, headers={"Range":"bytes=16-31"}, limit=16)
            assert status == 206 and len(data) == 16 and headers["Content-Range"] == "bytes 16-31/"+item.attrib["size"]
        read_video()  # A valid Range too early must not mark the guide as verified.
        print("PASS early Range; waiting for the one-minute threshold", flush=True)
        time.sleep(max(0, locked+61-time.monotonic()))
        assert fetch(url, method="HEAD")[0] == 200
        assert fetch(base+"/description.xml")[0] == 200
        log = call("logcat","-d","-s","ZLNA-test:I","ZLNA:I").split("locked "+token)[-1]
        assert "Screen-off check passed" not in log, "HEAD/description must not verify media"
        read_video()
        out = runner.communicate(timeout=15)[0]
        assert "OK (1 test)" in out, out
        print("PASS late Range, on-device verification and reset on stop")
    finally:
        if mapping: call("emu","redir","del",mapping)
        if runner.poll() is None: runner.terminate()

if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--adb", default="adb"); p.add_argument("--serial", required=True)
    opts=p.parse_args(); run(opts.adb,opts.serial)
