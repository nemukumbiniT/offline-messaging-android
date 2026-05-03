# Nexa – Offline-First Peer-to-Peer Messaging App

## 📌 Overview

**Nexa** is a secure, offline-first messaging application designed for environments with limited or no internet connectivity. It enables peer-to-peer communication using **Bluetooth** and **Wi-Fi Direct** (via Google Nearby Connections API), while supporting **mesh networking** to relay messages across multiple devices.

The app focuses on **privacy, resilience, and accessibility**, making it ideal for rural areas, township communities, disaster scenarios, or users who need reliable communication without internet access.

---

## 🚀 Key Features

### 🔌 Offline Messaging

* No internet required
* Uses Bluetooth & Wi-Fi Direct
* Messages delivered directly or relayed through nearby peers

### 🔐 End-to-End Encryption

* Powered by **Libsodium (NaCl)**
* Public/private key cryptography
* Secure key exchange and encrypted communication

### 📡 Peer Discovery

* Real-time nearby device detection
* Continuous BLE/Wi-Fi scanning
* Manual refresh support

### 🔁 Delay-Tolerant Networking (DTN)

* Store-and-forward messaging
* Messages queued when recipients are offline
* Automatic delivery when peers reconnect

### 📱 QR Code Pairing

* Fast and secure contact exchange
* Instant connection via QR scanning

### 👥 Peer Management

* Trust levels: **Trusted / Open / Blocked**
* Control who can message you
* View connection status and device info

### 💬 Chat System

* Real-time messaging interface
* Conversation history
* Message status indicators (Sent / Delivered)

### 🔔 Notifications

* Foreground service ensures continuous operation
* Real-time alerts for incoming messages

---

## 🧭 User Workflow

### 1. Authentication

* Launch app → Login or Sign Up
* Optional “Remember Me”
* Unique device ID generated locally
* Permissions required:

  * Bluetooth, Wi-Fi, Location
  * Camera (QR scanning)
  * Media & Audio
  * Notifications

### 2. Home Screen (Peer Discovery)

* Displays nearby peers in real time
* Filter by:

  * All / Nearby / Trusted / Open / Blocked
* Actions:

  * Refresh peers
  * Connect to peer
  * Scan QR code

### 3. QR Code Exchange

* Display your QR (Device ID + encryption keys)
* Scan another user’s QR
* Instant secure pairing and messaging

### 4. Chats Screen

* View all conversations
* Search and filter chats
* Tap to open a conversation

### 5. Messaging

* Send encrypted messages
* Routed via Nearby Connections or DTN
* Offline queueing supported

---
##🧑‍💻 My Contributions##

⚙️ Key Systems Implemented

🔐 Security Framework

* End-to-end encryption key infrastructure
* Public/private key management
* Secure peer communication (shared secrets)
* Trust-based messaging controls
  
💬 Messaging Infrastructure

* Message storage and lifecycle design
* Support for encrypted communication
* Message state tracking (sent/delivered)

👤 Authentication & User Management
* User registration and login flow
* Persistent sessions and auto-login
* Profile data handling

💾 Data Management
* Database schema design (SQLite + Room)
* Repository-style data access
* Background data operations with coroutines

---
## 🏗️ Architecture

### 📡 Communication Layer (`CommsLayer/`)

* `NearbyService.kt`

  * Peer discovery & connection lifecycle
  * Endpoint management (Bluetooth/Wi-Fi)
  * Handshakes and heartbeats
* DTN Module

  * Message relaying
  * Offline queueing

### 💾 Data Layer (`DataLayer/`)

* `AppDatabase` (Room ORM)
* Entities:

  * Messages
  * Peer Profiles
  * Conversations
* `DatabaseRepository` for persistence

### 🔔 Messaging Service

* `NexaMessagingService`

  * Foreground service
  * Handles incoming messages
  * Sends notifications
  * Routes messages

### 🧠 Business Logic (`logic/`)

* `MessageCenter`

  * Routing and dispatch
  * Delivery confirmation
* `EnhancedMsgHandler`

  * Advanced message handling

### 🔐 Security (`utils/`)

* `EncryptionService`
* `KeyManager`
* Cryptography:

  * NaCl (Libsodium)
  * AES / ChaCha20-Poly1305

### 🎨 UI Layer (`ui/`)

* `LoginScreen`
* `SignUpScreen`
* `HomeScreen`
* `ChatsScreen`
* `InChatsActivity`
* `QRCodeActivity`
* `ProfileScreen`
* `SettingsScreen`
* `GroupScreen`

---

## 🔄 Data Flow

1. User sends message
2. Message encrypted locally
3. Routed via Nearby Connections or DTN
4. Stored if recipient is unavailable
5. Delivered when peer becomes reachable
6. Decrypted on recipient device

---

## 🛠️ Tech Stack

* **Kotlin** + Coroutines
* **Google Nearby Connections API**
* **Libsodium (NaCl)**
* **Room Database (SQLite ORM)**
* **LiveData & Flow**
* **View Binding**
* **ZXing (QR Code)**

---

## 🔐 Security Model

* End-to-end encryption
* Device-specific key pairs
* Public key infrastructure
* Trust-based messaging controls
* Optional **“Trusted Only” mode**

---

## 👥 Contributors

This project was developed as part of a university capstone project.

* Thanyani Nemukumbini
* Tasima Hapazari
* Siyabonga Popela

This repository is maintained by **Thanyani Nemukumbini** for portfolio purposes.
Original development was done collaboratively, and all contributors retain rights to their work.

---

## 🧾 Proof of Contribution

Development history exists in the original GitLab repository, reflecting individual contributions through commit history.

---

## 📦 Installation

```bash
git clone https://github.com/nemukumbiniT/offline-messaging-android
cd offline-messaging-android
```

Open the project in **Android Studio or IntelliJ IDEA**, then run it on a physical Android device (Bluetooth & Wi-Fi required).

---

## ⚠️ Requirements

* Android device with:

  * Bluetooth & Wi-Fi support
  * Location services enabled
* Required permissions must be granted

---

## 🌍 Use Cases

* Rural or low-connectivity environments
* Disaster communication systems
* Privacy-focused messaging
* Local peer-to-peer networking

---

## 📄 License

This project is shared for **portfolio and demonstration purposes only**.
See the `LICENSE` file for full terms.

---

## ✨ Summary

**Nexa** is a decentralized, secure, and resilient messaging platform that works entirely offline using peer-to-peer and mesh networking technologies—enabling communication where traditional networks fail.
