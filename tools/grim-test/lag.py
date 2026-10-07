#!/usr/bin/env python3
"""A TCP proxy that adds latency, so the dev client can play with 2b2t-like ping.

  ./lag.py            100 ms each way (200 ms round trip), listening on LAG_PORT
  ./lag.py 75 15      75 ms each way with up to 15 ms of random jitter

Connect the client to localhost:LAG_PORT instead of PORT. Order is kept (it's one TCP stream), like real lag.
"""
import asyncio, os, random, sys, time

def env():
    vals = {}
    for line in open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "versions.env")):
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.strip().split("=", 1)
            vals[k] = v
    return vals

E = env()
DELAY = (float(sys.argv[1]) if len(sys.argv) > 1 else 100) / 1000
JITTER = (float(sys.argv[2]) if len(sys.argv) > 2 else 0) / 1000

async def pipe(reader, writer):
    queue = asyncio.Queue()
    async def receive():
        while data := await reader.read(65536):
            await queue.put((time.monotonic() + DELAY + random.uniform(0, JITTER), data))
        await queue.put(None)
    async def send():
        last = 0
        while (item := await queue.get()) is not None:
            due, data = item
            due = max(due, last)  # jitter never reorders the stream
            last = due
            await asyncio.sleep(max(0, due - time.monotonic()))
            writer.write(data)
            await writer.drain()
        writer.close()
    await asyncio.gather(receive(), send())

async def handle(client_reader, client_writer):
    server_reader, server_writer = await asyncio.open_connection("127.0.0.1", int(E["PORT"]))
    await asyncio.gather(pipe(client_reader, server_writer), pipe(server_reader, client_writer), return_exceptions=True)

async def main():
    server = await asyncio.start_server(handle, "127.0.0.1", int(E["LAG_PORT"]))
    print(f"localhost:{E['LAG_PORT']} -> localhost:{E['PORT']} with {DELAY*1000:.0f} ms each way (+{JITTER*1000:.0f} jitter)")
    async with server:
        await server.serve_forever()

asyncio.run(main())
