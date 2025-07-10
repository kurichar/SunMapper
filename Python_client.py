import asyncio
import websockets
import numpy as np
import cv2
import torch
from torch.amp import autocast
from PIL import Image
from transformers import AutoProcessor, AutoModelForZeroShotObjectDetection
import json
import requests
import pvlib
import pandas as pd
import socket, time
from zeroconf import Zeroconf, ServiceBrowser, ServiceListener


class MdnsListener(ServiceListener):
    def __init__(self):
        # Initialize with no address or port until a service is discovered
        self.address = None
        self.port    = None

    def add_service(self, zc, type_, name):
        info = zc.get_service_info(type_, name)
        if info:
            # Extract the first IPV4 address and port from the service info
            self.address = socket.inet_ntoa(info.addresses[0])
            self.port    = info.port
            print(f"→ Found SunlightMapper at {self.address}:{self.port}")

    def remove_service(self, *args):
        # Not used
        pass
    def update_service(self, *args):
        # Not used
        pass

# Create a Zeroconf instance and register our listener for the custom service type
zc = Zeroconf()
listener = MdnsListener()
browser = ServiceBrowser(zc, "_sunlight-zone._tcp.local.", listener)

# give it a moment to discover
time.sleep(2)

# Poll until the Android app shows up on mDNS
print("🔍 Waiting for Android KtorServer via mDNS… (run the app now)")
while listener.address is None:
    time.sleep(1)

# Once discovered, use the stored address and port
host = listener.address        
port = listener.port         

# Build base URLs for HTTP and WebSocket endpoints
HTTP_BASE = f"http://{host}:{port}"     
WS_BASE   = f"ws://{host}:{port}"         

# Define endpoints for bounding-box updates, sun-vector updates, and frame streaming
BBOX_URL = HTTP_BASE + "/bbox"              
SUN_URL  = HTTP_BASE + "/sun"              
FRAME_WS  = WS_BASE + "/ws/frame"          

print("Using:", HTTP_BASE , WS_BASE)




async def display_and_use_sun():

    #Load GroundingDINO
    model_id = "IDEA-Research/grounding-dino-base"
    device   = "cuda" if torch.cuda.is_available() else "cpu"
    processor = AutoProcessor.from_pretrained(model_id)
    model     = AutoModelForZeroShotObjectDetection.from_pretrained(model_id)
    model.to(device).eval()

    # Create a named window for displaying frames
    cv2.namedWindow("Window Detection", cv2.WINDOW_NORMAL)
    announce_retry = True
    # Start the WebSocket connection loop
    while True:
        try:
            if announce_retry:
                print("🔗 Connecting to frame socket…")
                announce_retry = False
            async with websockets.connect(FRAME_WS, ping_interval=None) as ws:
                print("✅ Connected — waiting for handshake(s)…")
                announce_retry = True
                sun_enu = None

                # Wait for the first message to get the sun vector
                async for message in ws:
                    # If the message is a JSON string, it contains metadata about the sun position
                    if isinstance(message, str):
                        meta = json.loads(message)
                        lat = meta.get("lat")
                        lon = meta.get("lon")
                        tz  = meta.get("tz", "UTC")

                        # pick up date/hour
                        if "date" in meta and "hour" in meta and "minute" in meta:
                            h = int(meta["hour"])
                            m = int(meta["minute"])
                            t_str = f"{meta['date']} {h:02d}:{m:02d}:00"
                            now   = pd.Timestamp(t_str, tz=tz)
                        else:
                            now = pd.Timestamp.now(tz=tz)

                        print(f"Data received: lat={lat}, lon={lon}, tz={tz}, now={now}")

                        # Compute sun vector ENU → unit vector
                        sol = pvlib.solarposition.get_solarposition(
                            now, latitude=lat, longitude=lon
                        )
                        # Convert zenith and azimuth to ENU vector
                        zen = np.deg2rad(sol["zenith"].iat[0])
                        azi = np.deg2rad(sol["azimuth"].iat[0])
                        sun_enu = np.array([
                            np.sin(zen) * np.sin(azi),  # East
                            np.sin(zen) * np.cos(azi),  # North
                            np.cos(zen)                 # Up
                        ])

                        print("☀️ Sun ENU vector:", sun_enu)
                        # Send the sun vector to the Android app
                        e, n, u = sun_enu.tolist()
                        requests.post(SUN_URL, json={"east": e, "north": n, "up": u})
                        print("✅ Sent sun ENU to Android")

                        continue

                    # If the message is binary, it contains an image frame       
                    arr = np.frombuffer(message, np.uint8)
                    bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)

                    if bgr is None:
                        continue
                    
                    H, W = bgr.shape[:2]
                    pil = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
                    # Prepare inputs for the model
                    inputs = processor(images=pil, text="window.", return_tensors="pt")
                    inputs = {k: v.to(device) for k,v in inputs.items()}

                    # Perform inference
                    with torch.no_grad(), autocast('cuda'):
                        outputs = model(**inputs)

                    # Post-process the outputs to get bounding boxes
                    result = processor.post_process_grounded_object_detection(
                        outputs=outputs,
                        input_ids=inputs["input_ids"],
                        box_threshold=0.4,
                        text_threshold=0.3,
                        target_sizes=[(H, W)]
                    )[0]

                    boxes  = result["boxes"].cpu().numpy()
                    scores = result["scores"].cpu().numpy()

                    if boxes.size:
                        # Draw the best bounding box on the image
                        idx = int(np.argmax(scores))
                        x0, y0, x1, y1 = boxes[idx].astype(int)
                        cv2.rectangle(bgr, (x0,y0), (x1,y1), (0,255,0), 2)
                        
                        # Send the bounding box coordinates to the Android app
                        requests.post(BBOX_URL, json={
                            "x0": x0/W, "y0": y0/H,
                            "x1": x1/W, "y1": y1/H
                        })

                    cv2.imshow("Window Detection", bgr)
                    if cv2.waitKey(1) & 0xFF == 27:
                        return

                cv2.destroyWindow("Window Detection")

                
                while True:
                    print("☀️ Sun vector (ENU):", sun_enu)
                    await asyncio.sleep(1)

        except Exception as e:
            if announce_retry:
                print("⚠️ Stream error:", repr(e))
                await asyncio.sleep(1)
            else:
                await asyncio.sleep(1)
            

if __name__ == "__main__":
    asyncio.run(display_and_use_sun())
