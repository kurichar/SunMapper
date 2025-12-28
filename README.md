# SunMapper
Plan indoor plant placement or furniture positioning by visualizing exactly where sunlight will fall throughout the day and year.
---

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
so it is recommended to install PyTorch with CUDA support.
Make sure to install the version compatible with your CUDA version.

For example, for CUDA 11.8:
```bash
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu118
```
## Install other dependencies
```bash
pip install -r requirements.txt
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

## Demonstrations:
Detecting a window and locking it in AR view:

![window selection](https://github.com/user-attachments/assets/4d14fba9-631d-46ba-8b97-da2bdd86e0b8)


Changing time slider:

![time slider](https://github.com/user-attachments/assets/54f50d92-e6f8-4156-ac1d-865d84090b50)


Changing the date:

![date change](https://github.com/user-attachments/assets/62291b99-7755-4e08-bd91-84b0a79d54a6)

## Additional 'nice to have' features:
Floating cardinal markers:

![cardinal markers](https://github.com/user-attachments/assets/2d5046b0-e171-4501-b46b-03ebc79035a8)


Floor continuation:

![floor continuation](https://github.com/user-attachments/assets/1922c225-6b29-473e-92a9-e4291e7313bd)





