"""SITL only: a telemetry link that can be cut. Carries one SITL vehicle's MAVLink between its TCP port and a UDP port
the GCS listens on, as a radio would. Kill this script and the GCS hears nothing more from that vehicle, while the
vehicle goes on flying; start it again and the link is back. For the Pass 32 check ("one link killed and restored").

    py -3.9 tools/sitl/link_relay.py 5782 14553

5782: the vehicle's SERIAL1 (SITL instance 2). 14553: add a "UDP listen" link on that port in the GCS.
Plain sockets, no MAVLink parsing: bytes in, bytes out.
"""
import select
import socket
import sys


def main():
    tcp_port, udp_port = int(sys.argv[1]), int(sys.argv[2])
    vehicle = socket.create_connection(("127.0.0.1", tcp_port))
    gcs = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    gcs_address = ("127.0.0.1", udp_port)
    print(f"relaying tcp:{tcp_port} <-> udp:{udp_port}", flush=True)
    while True:
        readable, _, _ = select.select([vehicle, gcs], [], [], 1.0)
        for s in readable:
            if s is vehicle:
                data = vehicle.recv(4096)
                if not data:
                    return
                gcs.sendto(data, gcs_address)
            else:
                try:
                    data, _ = gcs.recvfrom(4096)
                except ConnectionResetError:
                    continue  # Windows reports "nobody listening on the UDP port yet" this way; keep going
                vehicle.sendall(data)


if __name__ == "__main__":
    main()
