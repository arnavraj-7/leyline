"""Fill the DroidRush abstract template with Leyline content.

Usage: python build_abstract.py <unpacked-template-dir> <out.docx> <classdiagram.png> <flowdiagram.png>
"""
import os
import re
import shutil
import struct
import sys
import tempfile
import zipfile
from xml.sax.saxutils import escape

TPL, OUT, CLASS_PNG, FLOW_PNG = sys.argv[1:5]

FONT = '<w:rFonts w:ascii="Cambria" w:cs="Cambria" w:eastAsia="Cambria" w:hAnsi="Cambria"/>'
MONO = '<w:rFonts w:ascii="Consolas" w:cs="Consolas" w:eastAsia="Consolas" w:hAnsi="Consolas"/>'
PINK = 'e01b84'


def rpr(bold=False, color=None, size=20, italic=False, mono=False, highlight=False):
    parts = [MONO if mono else FONT]
    if bold:
        parts.append('<w:b w:val="1"/><w:bCs w:val="1"/>')
    if italic:
        parts.append('<w:i w:val="1"/><w:iCs w:val="1"/>')
    if color:
        parts.append(f'<w:color w:val="{color}"/>')
    parts.append(f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>')
    if highlight:
        parts.append('<w:highlight w:val="yellow"/>')
    return '<w:rPr>' + ''.join(parts) + '</w:rPr>'


def runs(text, size=20, color=None, base_bold=False, mono=False):
    """Inline markup: **bold**, `mono`, and {{placeholder}} highlighted yellow."""
    out = []
    for chunk in re.split(r'(\*\*.+?\*\*|`.+?`|\{\{.+?\}\})', text):
        if not chunk:
            continue
        bold, is_mono, hl = base_bold, mono, False
        if chunk.startswith('**'):
            chunk, bold = chunk[2:-2], True
        elif chunk.startswith('`'):
            chunk, is_mono = chunk[1:-1], True
        elif chunk.startswith('{{'):
            chunk, hl = chunk[2:-2], True
        out.append(f'<w:r>{rpr(bold, color, size, mono=is_mono, highlight=hl)}'
                   f'<w:t xml:space="preserve">{escape(chunk)}</w:t></w:r>')
    return ''.join(out)


def para(text='', align='both', after=160, before=0, num=None, size=20, color=None, bold=False,
         keep_next=False, mono=False):
    ppr = []
    if keep_next:
        ppr.append('<w:keepNext/>')
    if num is not None:
        ppr.append(f'<w:numPr><w:ilvl w:val="0"/><w:numId w:val="{num}"/></w:numPr>')
    ppr.append(f'<w:spacing w:before="{before}" w:after="{after}" w:line="276" w:lineRule="auto"/>')
    if num is not None:
        ppr.append('<w:ind w:left="720" w:hanging="360"/>')
    ppr.append(f'<w:jc w:val="{align}"/>')
    return f'<w:p><w:pPr>{"".join(ppr)}</w:pPr>{runs(text, size, color, bold, mono)}</w:p>'


def bullet(text, after=60):
    return para(text, num=3, after=after)


def numbered(text, after=60):
    return para(text, num=1, after=after)


def subhead(text):
    return para(text, align='left', before=200, after=80, size=22, color=PINK, bold=True, keep_next=True)


def heading(text):
    return ('<w:p><w:pPr><w:pStyle w:val="Heading1"/><w:keepNext/><w:spacing w:after="80" w:before="300"'
            f' w:line="276" w:lineRule="auto"/></w:pPr><w:r><w:rPr>{FONT}<w:smallCaps w:val="1"/>'
            f'<w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr><w:t xml:space="preserve">{escape(text)}'
            '</w:t></w:r></w:p>')


def table(widths, rows, size=17):
    total = sum(widths)
    border = ''.join(f'<w:{s} w:val="single" w:sz="4" w:space="0" w:color="BFBFBF"/>'
                     for s in ('top', 'left', 'bottom', 'right', 'insideH', 'insideV'))
    xml = [f'<w:tbl><w:tblPr><w:tblW w:w="{total}" w:type="dxa"/><w:tblBorders>{border}</w:tblBorders>'
           '<w:tblLayout w:type="fixed"/><w:tblCellMar><w:top w:w="60" w:type="dxa"/>'
           '<w:left w:w="90" w:type="dxa"/><w:bottom w:w="60" w:type="dxa"/>'
           '<w:right w:w="90" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid>']
    xml += [f'<w:gridCol w:w="{w}"/>' for w in widths]
    xml.append('</w:tblGrid>')
    for r, row in enumerate(rows):
        header = r == 0
        xml.append('<w:tr>' + ('<w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>' if header
                                else '<w:trPr><w:cantSplit/></w:trPr>'))
        for w, cell in zip(widths, row):
            shade = '<w:shd w:val="clear" w:color="auto" w:fill="F7E1EE"/>' if header else ''
            paras = ''.join(
                f'<w:p><w:pPr><w:spacing w:before="0" w:after="40" w:line="252" w:lineRule="auto"/></w:pPr>'
                f'{runs(line, size, base_bold=header)}</w:p>' for line in cell.split('\n'))
            xml.append(f'<w:tc><w:tcPr><w:tcW w:w="{w}" w:type="dxa"/>{shade}</w:tcPr>{paras}</w:tc>')
        xml.append('</w:tr>')
    xml.append('</w:tbl>')
    return ''.join(xml) + para('', after=80)


def png_size(path):
    with open(path, 'rb') as f:
        head = f.read(24)
    return struct.unpack('>II', head[16:24])


IMAGES = []


def image(path, width_in, caption):
    w, h = png_size(path)
    cx = int(width_in * 914400)
    cy = int(cx * h / w)
    n = len(IMAGES) + 1
    rid = f'rIdImg{n}'
    IMAGES.append((rid, f'image{n}.png', path))
    drawing = (
        f'<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="{cx}" cy="{cy}"/>'
        f'<wp:docPr id="{100 + n}" name="Figure {n}"/><wp:cNvGraphicFramePr>'
        '<a:graphicFrameLocks xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" noChangeAspect="1"/>'
        '</wp:cNvGraphicFramePr><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">'
        '<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
        '<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">'
        f'<pic:nvPicPr><pic:cNvPr id="{100 + n}" name="image{n}.png"/><pic:cNvPicPr/></pic:nvPicPr>'
        f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
        f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>'
        '</wp:inline></w:drawing></w:r>')
    return (f'<w:p><w:pPr><w:keepNext/><w:spacing w:before="120" w:after="60"/><w:jc w:val="center"/></w:pPr>'
            f'{drawing}</w:p>'
            + para(caption, align='center', after=200, size=17, color='595959'))


# ---------------------------------------------------------------- content
doc = open(os.path.join(TPL, 'word', 'document.xml'), encoding='utf8').read()
paras = re.findall(r'<w:p[ >].*?</w:p>', doc, flags=re.S)
body_start = doc.index('<w:body>') + len('<w:body>')
sect = re.search(r'<w:sectPr>.*?</w:sectPr>', doc, flags=re.S).group(0)

title = paras[0]
team = paras[1].replace('Team: &lt;insert name&gt;', 'Team: Simpletons')
team_id = paras[2].replace('&lt;Insert CodeSangam Website Team ID here&gt;', '15')
team_info = paras[3]

B = []
B += [title, team, team_id, team_info]
B.append(para('Member 1: **Ayush Jadaun** (20244048, ECE, ayush-jadaun, ayush_jadaun) — team leader', after=60))
B.append(para('Member 2: **Arnav Raj** (20244036, ECE, arnavraj-7, arnav050148)', after=60))
B.append(para('Member 3: **Abhay Agarwal** (20245003, Electrical Engineering, Abhay030405, abhayagarwal3751)'))

# ---- Description
B.append(heading('Description of the problem'))
B.append(para(
    'Problem statement: **The Blackout Leyline** (PS 07). '
    'When a flood, earthquake or grid failure takes down cell towers and Wi-Fi, phones go silent even when '
    'people are only a few hundred metres apart. Yet almost every phone still carries a Bluetooth Low Energy '
    '(BLE) radio that needs no infrastructure at all. One BLE link reaches only about 10–30 m indoors, but a '
    'chain of phones can carry a message much further if each phone relays it to the next.'))
B.append(para(
    'We propose **Leyline**, an Android app that will turn every phone into a relay in a delay-tolerant mesh '
    'network. A message from phone A will reach phone C through phone B, even though A and C are never in range '
    'of each other. If no path exists at that moment, the message will be stored and forwarded the moment a '
    'neighbour appears. Leyline will work in airplane mode with only Bluetooth switched on, SOS messages will '
    "always travel ahead of ordinary chat, and every SOS will carry the sender's GPS position (GPS needs no "
    'network), so rescuers will know not only that someone needs help but where.'))
B.append(para('App name: Leyline', color=PINK, bold=True))

# ---- Process flow
B.append(heading('Process flow of the app'))
flow = [
    '**First launch:** the app will generate a random 32-bit node ID and store it. Android rotates BLE addresses '
    'every few minutes for privacy, so all routing will use this ID instead of the address.',
    '**Permissions:** the app will request Bluetooth scan/advertise/connect on Android 12+, location on Android 11 '
    'and below (required there for BLE scanning), plus notifications. Each permission will be explained before it '
    'is requested.',
    '**Join the mesh:** a foreground service will advertise a shared Leyline service UUID carrying the node ID, '
    'scan for other Leyline nodes, connect to nearby ones as a GATT client, and run its own GATT server so others '
    'can connect to it.',
    '**Send:** the user will type a message (under 280 characters) or press SOS. The router will give it a unique '
    'message ID, a TTL (3–5 hops) and a priority, and will **commit it to the outbox before anything is sent**.',
    '**Transmit:** the packet will be encoded in our binary format, split into chunks that fit the MTU negotiated '
    'for that connection (about 180 bytes), and written to every connected neighbour.',
    '**Relay:** each receiving phone will reassemble the chunks and check the message ID against its recently-seen '
    'set. Duplicates will be dropped. Otherwise the phone will deliver the message (if addressed to it or to '
    'everyone), decrement the TTL and, if the TTL allows, forward it to every neighbour except the one it came from.',
    '**Store and forward:** if no neighbour is connected, the message will wait in the outbox. When a phone '
    'reconnects, pending messages will be replayed to the new neighbour.',
    '**Receipt and display:** the destination will send a delivery receipt back along the reverse path. Each '
    'message will show sender, time and hop telemetry (hop count and the chain of relays it passed through).',
]
B += [numbered(t) for t in flow]
B.append(para('', after=60))

# ---- Features
B.append(heading('Features announcement'))
B.append(para('The features we plan to implement:', after=60))
B.append(subhead('Foundation (pass tier)'))
B += [bullet(t) for t in [
    '**BLE discovery:** foreground advertising and scanning on a shared service UUID; GATT connections with '
    'chunked writes sized from the negotiated MTU.',
    '**Store-and-forward flood router:** message-ID deduplication, TTL 3–5, persistent outbox replayed on reconnect.',
    '**Multi-hop SOS:** 1-hop and 2-hop SOS messages (under 280 characters) showing sender, timestamp and hop path.',
    '**Disaster mode:** full operation in airplane mode with Bluetooth on.',
]]
B.append(subhead('Victory tier'))
B += [bullet(t) for t in [
    '**Background relay** in a foreground service with a priority queue: SOS will always go before chat.',
    '**Long-message reassembly**, automatic reconnect with backoff, SOS beacon mode, and **message signing** so '
    'that a relay will not be able to forge who sent a message.',
    '**Wi-Fi Direct escalation** for large payloads, and a **4+ node broadcast-storm stress test**.',
]]
B.append(subhead('Our additions beyond the problem statement'))
B += [bullet(t) for t in [
    '**Location in every SOS:** GPS works without any network, so each SOS will carry coordinates and accuracy. '
    'Receivers will see the distance and compass direction to the sender.',
    '**Delivery receipts:** acknowledgements will travel back along the reverse path, so the sender will see '
    '"SOS reached 4 people, nearest 2 hops away" instead of hoping.',
    '**Live mesh map:** a real-time graph of nearby nodes, link strength and the path each message took.',
    '**Battery-aware relaying:** phones low on battery will relay less and announce it; healthier phones will '
    'carry more load.',
    '**One-tap SOS anywhere:** a Quick Settings tile will send an SOS without opening the app.',
    '**Mesh health dashboard:** live delivery rate, latency, duplicates suppressed and bytes relayed — measured, '
    'not claimed.',
    '**Engineering tooling:** a JVM simulator that will run dozens of virtual nodes on the same routing code, a '
    'topology mode that will force multi-hop paths in a small room (always shown in the UI), and an in-app test log.',
]]
B.append(para('', after=60))

# ---- Libraries
B.append(heading('External libraries/APIs'))
B.append(para(
    'We compared each choice on the template\'s criteria: active development, maintainer, licence, community '
    'size and whether it supports what a mesh needs out of the box. GitHub figures are as of October 2026.'))
lib_rows = [
    ['Need', 'Chosen', 'Alternatives considered', 'Evidence'],
    ['GATT client and server', '**Nordic Android-BLE-Library** 2.11 + ble-ktx',
     'Raw Android BLE APIs\nKable (JUUL Labs)\nNordic Kotlin-BLE-Library',
     'Nordic: 2,423★, BSD-3-Clause, maintained by Nordic Semiconductor, last commit Oct 2026.\n'
     'Kable: 1,201★, Apache-2.0 — client role only.\nKotlin-BLE-Library: 530★, v2.0 still beta.'],
    ['Advertising and scanning', '**Android platform APIs**', 'Nordic scanner-compat library',
     'Built into Android since API 21; we will target API 26+, so no compatibility layer will be needed.'],
    ['Local storage', '**Room** 2.8 (SQLite)', 'Raw SQLite\nSQLDelight',
     'Room: Google/AndroidX, Apache-2.0.\nSQLDelight: 6,897★, Apache-2.0 — built for multiplatform; Leyline will '
     'be Android-only.'],
    ['Concurrency', '**kotlinx.coroutines** 1.11 + Flow', 'RxJava\nThreads + callbacks',
     '13,823★, Apache-2.0, JetBrains. The current Android standard.'],
    ['UI', '**Jetpack Compose + Material 3**', 'XML Views',
     'Google, Apache-2.0, BOM 2026.09. Google\'s recommended UI toolkit.'],
    ['Packet encoding', '**Our own binary format**', 'Protocol Buffers\nJSON',
     'No dependency; every byte will be documented in our packet specification.'],
    ['Testing', '**JUnit 6** + coroutines-test', '—', 'Standard JVM testing, EPL-2.0.'],
]
B.append(table([1500, 1900, 2000, 3960], lib_rows))
B.append(subhead('Why these choices'))
B += [bullet(t) for t in [
    '**Nordic over raw APIs:** Android allows only one GATT operation at a time per connection, and its BLE stack '
    'has phone-specific failures (status 133, stale service caches). Nordic\'s library queues operations, retries '
    'and negotiates MTU, and it supports **both** client and server roles. Rebuilding that would mean re-solving '
    'solved problems instead of building the mesh.',
    '**Not Kable:** a mesh node must accept incoming connections (server role); Kable only supports the client role.',
    '**Not Nordic\'s Kotlin rewrite:** still in beta (2.0.0-beta07); we will not demo on a beta transport.',
    '**Room over SQLDelight:** both are solid; Room gives compile-time-checked SQL, transactions and Flow-based '
    'UI updates with less setup for an Android-only app.',
    '**Our own packet format over Protobuf/JSON:** JSON is far too large for 180-byte chunks; Protobuf adds a '
    'dependency and hides the wire layout the problem statement asks us to specify.',
    '**Dependency wiring:** we will use a single hand-written container instead of Hilt/Koin — no generated code, '
    'no runtime surprises, and every object\'s lifetime visible in one place.',
]]
B.append(para('Every library and AI tool we use will be disclosed in the project README.'))

# ---- Architecture
B.append(heading('Core Engineering Architecture'))
B.append(para('This section describes the architecture we will implement.'))
B.append(subhead('Infrastructure Layout & Data Flow'))
B.append(para(
    'Leyline will have **no server**: every phone will be an equal node running the same four layers — Compose UI, '
    'a foreground MeshService, the routing core (**mesh-core**, pure Kotlin with no Android code) and the Bluetooth '
    'layer (**mesh-ble**). Because mesh-core will have no Android dependency, the exact routing code that runs on '
    'the phone will also run in unit tests and in a JVM simulator with dozens of virtual nodes.'))
B.append(para(
    'When a user sends an SOS, it will flow: UI → MeshService → router (assigns ID, TTL and priority) → outbox row '
    '**committed to Room first** → codec → chunker (splits by MTU) → GATT write to each neighbour. Receiving will '
    'run the reverse: chunks → reassembler → codec → router (dedup → deliver → forward). Caching will be the '
    'seen-set of message IDs; storage will be Room (outbox, message history, receipts).'))
B.append(image(FLOW_PNG, 2.6, 'Figure 1 — Planned data flow for one SOS from phone A to phone C through relay B'))

B.append(subhead('Resource perspective: what happens under load'))
B.append(para(
    'In a mesh, "heavy load" is not a server rush — it is a **broadcast storm**: twenty people in one shelter, '
    'every phone retransmitting every message. Unmanaged, the radios saturate and SOS messages drown. Every '
    'resource in Leyline will be bounded:'))
B += [bullet(t) for t in [
    '**Radio:** each node will forward a message at most once (dedup). Rebroadcasts will wait a short random '
    'delay, and a node will skip forwarding if it has already heard enough neighbours forward the same message '
    '(counter-based suppression).',
    '**Connections:** the connection pool will keep a capped number of neighbours, preferring the strongest '
    'signal — protecting battery and staying within the per-phone connection limit.',
    '**Memory:** the seen-set will be bounded by size and by age, so a relay running for hours will not grow '
    'without limit.',
    '**Queue:** a bounded priority queue. SOS will never be dropped; when the queue is full, the oldest ordinary '
    'chat will be evicted first.',
    '**Battery:** scanning will run in duty cycles, and the service will run as a "connected device" foreground '
    'service so Android does not kill it mid-relay.',
]]

B.append(subhead('Production-grade, not a classroom assignment'))
cases = [
    ('Forwarding messages',
     'Rebroadcast every packet received. Two phones in range bounce the same message back and forth forever, '
     'and in a crowd traffic multiplies at every hop until the links saturate.',
     'Every packet will carry a unique 64-bit message ID, a TTL and the list of relays it passed. A node will drop '
     'IDs it has seen, drop packets whose relay list already contains itself, decrement the TTL, never send back to '
     'the neighbour it came from, and suppress redundant rebroadcasts.',
     'Each node will transmit each message at most once and no message will travel further than its TTL, so total '
     'traffic will be bounded and loops impossible. The README will include the proof, and every rule will be '
     'covered by unit tests.'),
    ('Writing to BLE connections',
     'Call a GATT write for each chunk the moment it is ready. Android allows only one GATT operation in flight per '
     'connection — the second call fails silently and chunks vanish.',
     'A per-connection serial operation queue with retry and exponential backoff on Android\'s common GATT failure '
     'codes (8 = connection timeout, 22 = terminated by local host, 133 = generic error).',
     'Every chunk will be written in order or retried; an unstable link will cost latency, never corrupt a message.'),
    ('Not losing messages',
     'Keep pending messages in an in-memory list. If Android kills the app or the phone restarts, every undelivered '
     'SOS is gone.',
     'Write-ahead persistence: a message will be committed to the Room outbox before its first transmission. The '
     'message ID will be the primary key, so retries and duplicates cannot insert twice (idempotent). Delivery state '
     'will be tracked per neighbour and replayed on reconnect.',
     'A message will survive process death, reboot and long disconnection; store-and-forward will resume exactly '
     'where it stopped.'),
    ('Identifying phones',
     'Use the Bluetooth MAC address as identity. Android rotates BLE addresses every few minutes for privacy, so '
     'the same phone shows up under several addresses within half an hour — dedup and routing break.',
     'A persistent random node ID carried inside every advertisement and every packet.',
     'Identity will be independent of the radio layer, stay stable across rotations, and make message signing '
     'possible.'),
    ('Packet size',
     'Hard-code one size, or the 20-byte default payload. Android 14+ always negotiates MTU 517 while older phones '
     'settle on lower values such as 247 — a fixed size is either wasteful or broken.',
     'Chunk size will come from the MTU actually negotiated on each connection (MTU − 3 bytes of ATT header), '
     'capped at about 180-byte chunks that fit old and new phones alike.',
     'It will work across old and new phones with no per-device configuration.'),
]
for name, naive, smart, why in cases:
    B.append(para(f'**{name}**', align='left', before=120, after=40, keep_next=True))
    B.append(bullet(f'**The Naive/Basic Way:** {naive}'))
    B.append(bullet(f'**Your Smart Engineering Way:** {smart}'))
    B.append(bullet(f'**Why It Wins:** {why}', after=120))

B.append(subhead('Hardware plan'))
B.append(para(
    'Leyline will target Android 8.0+ (API 26), which provides both BLE roles a mesh node needs: GATT server and '
    'GATT client. The multi-device demo and the 4+ node stress test will use Android phones of different ages, so '
    'both MTU behaviours (517 on Android 14+, lower values on older versions) will be covered.'))

# ---- Class diagram (own landscape section)
B.append('<w:p><w:pPr>' + sect.replace('<w:sectPr>', '<w:sectPr><w:type w:val="nextPage"/>', 1)
         + '</w:pPr></w:p>')
B.append(heading('Class diagram'))
B.append(para(
    '**mesh-core** (Packet, PacketCodec, SeenCache, Router, ForwardQueue, Chunker, Reassembler) will be pure '
    'Kotlin. **mesh-ble** will implement the Transport interface over GATT; the **app** module will implement '
    'Outbox with Room and host MeshService and the UI. Interfaces at the module boundaries will let the simulator '
    'replace BLE and Room with in-memory fakes.'))
B.append(image(CLASS_PNG, 8.2, 'Figure 2 — Planned class diagram of Leyline'))

landscape = (sect.replace('<w:pgSz w:h="15840" w:w="12240" w:orient="portrait"/>',
                         '<w:pgSz w:h="12240" w:w="15840" w:orient="landscape"/>')
             .replace('<w:pgNumType w:start="1"/>', ''))
assert 'landscape' in landscape
new_doc = doc[:body_start] + ''.join(B) + landscape + '</w:body></w:document>'
if 'xmlns:wp=' not in new_doc[:3000]:
    raise SystemExit('template lacks wp namespace')

# ---------------------------------------------------------------- package
work = tempfile.mkdtemp()
shutil.copytree(TPL, os.path.join(work, 'pkg'))
pkg = os.path.join(work, 'pkg')
open(os.path.join(pkg, 'word', 'document.xml'), 'w', encoding='utf8').write(new_doc)
os.makedirs(os.path.join(pkg, 'word', 'media'), exist_ok=True)
rels_path = os.path.join(pkg, 'word', '_rels', 'document.xml.rels')
rels = open(rels_path, encoding='utf8').read()
for rid, name, src in IMAGES:
    shutil.copy(src, os.path.join(pkg, 'word', 'media', name))
    rels = rels.replace('</Relationships>',
                        f'<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/'
                        f'relationships/image" Target="media/{name}"/></Relationships>')
open(rels_path, 'w', encoding='utf8').write(rels)
ct_path = os.path.join(pkg, '[Content_Types].xml')
ct = open(ct_path, encoding='utf8').read()
if 'Extension="png"' not in ct:
    ct = ct.replace('<Default ContentType="application/xml" Extension="xml"/>',
                    '<Default ContentType="application/xml" Extension="xml"/>'
                    '<Default ContentType="image/png" Extension="png"/>')
open(ct_path, 'w', encoding='utf8').write(ct)

if os.path.exists(OUT):
    os.remove(OUT)
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(pkg):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, pkg).replace(os.sep, '/'))
print('wrote', OUT, 'images:', len(IMAGES))
