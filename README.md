# SunMapper

A two-part system for real-time indoor sunlight mapping on floor surfaces.  
- **Android client** (ARCore/Ktor) detects ARCore environment and streams frames & metadata over mDNS.  
- **Python client** performs zero-shot window detection and sun-vector computation, and sends results back to the Android app.

---

## Prerequisites

- **Android** device or emulator with **ARCore** support  
- **Python 3.8+**  
- **NVIDIA GPU with CUDA** (strongly recommended for inference performance)  
- **Android Studio** for building the Android app
- Connected to the **same network** as the Android device

---

## Install PyTorch with CUDA support
This project uses the IDEA-Research/grounding-dino-base model, which runs much faster on GPU.

For example, for CUDA 11.8:
```bash
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu118
```
## How to use:
1. build the Android app using Android Studio
2. run the Python client:
```bash
python Python_client.py
```
3. open the Android app on your device **horizontally** and grant camera and location permissions
4. the app will automatically discover the Python client and start receiving geospatial and sun location in real-time
5. point the camera towards a window/wall with a window, and the app will display a detected plane via the cursor turning from purple sphere to a flat gray disk gliding on the surface, once that plane is detected, hold the button to find the window and wait for the bounding box to appear, once you're satisfied with the detection, release the button to save the window position
6. point the camera towards a floor surface, once that plane is detected, press the button to save the floor plane, indicated by a green cursor
7. the app will start displaying the sun rays on the selected floor in real-time coming from the sun and through the detected window, indicated by a translucent yellow polygon on the floor surface
8. you can adjust the time of day and date in the app to see how the sun position and rays changes over time

if anything goes wrong you can reset the scene by pressing the top right button, which will reset the detected window and floor planes.
