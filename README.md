<p align="center">
  <img src="src/main/resources/META-INF/pluginIcon.svg" alt="RestPilot Logo" width="120" height="120">
</p>

<h1 align="center">RestPilot ✈️</h1>

<p align="center">
  <b>The Missing Link Between Your Code and API Testing.</b><br>
  <i>Smart, Code-Aware, and Built for Spring Boot Developers.</i>
</p>

<p align="center">
  <a href="https://plugins.jetbrains.com/">
    <img src="https://img.shields.io/badge/IntelliJ-Plugin-blue.svg?style=flat-square&logo=intellij-idea" alt="IntelliJ Plugin">
  </a>
  <a href="https://phil-zhang.netlify.app/">
    <img src="https://img.shields.io/badge/Author-Phil%20Zhang-orange?style=flat-square" alt="Author">
  </a>
  <img src="https://img.shields.io/badge/License-Apache%202.0-green.svg?style=flat-square" alt="License">
</p>

---

## 🧐 Why RestPilot?

Are you tired of **context switching**?
Writing code in IntelliJ, switching to Postman to test, copying the JSON body manually, realizing you missed a field, switching back to code... 🤯

**RestPilot** stops the madness. It brings a powerful, Postman-grade REST client **directly into your IDE**, but with a superpower Postman will never have: **It understands your code.**

It scans your Spring Boot Controllers in real-time, generates smart JSON payloads from your DTOs, and lets you debug APIs without ever touching the mouse.

---

## ✨ Key Features

### 🧠 Smart DTO Analysis
Stop writing JSON by hand! RestPilot recursively analyzes your method parameters and DTO classes to generate **complete, pre-filled JSON bodies**.
- Handles nested objects (`User -> Address -> City`).
- Handles Generics (`List<UserDTO>`, `Page<Order>`).
- Generates smart default values for Strings, Integers, Enums, and Dates.

### 🔍 Live Code Scanning
Your API list is always up-to-date. RestPilot scans `Running` or `Static` code to find all endpoints defined with:
- `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`
- Just click the **Green Run Icon** in the editor gutter to start testing!

### ⚡ Seamless Interoperability
Don't work in a silo. RestPilot plays nice with the tools you already use.
- **cURL Support:** - **Import:** Paste a cURL command (from Chrome DevTools or Slack) directly into the address bar to auto-fill the request.
   - **Export:** One-click "Copy as cURL" to share reproduction steps with your team.
- **Postman Compatible:** Import your existing Collections or export your RestPilot tests back to Postman JSON format.

### 🔌 Protocol Mastery
- **Multipart Uploads:** Easily upload files and submit complex forms with a dedicated UI.
- **Cookie Manager:** Automatic session holding (JSESSIONID) with a "Clear Cookies" button for testing logout flows.
- **Auth Support:** Built-in support for Bearer Token, Basic Auth, and API Keys.

### 🔗 Automation & Workflow
- **Extract Variables:** Automatically extract values (like Tokens) from a JSON response and save them to Environment Variables for the next request.
- **Environments:** Manage multiple environments (Local, Dev, Prod) with ease.

### 🧭 Bi-Directional Navigation
- **Tree to Code:** Jump from the API list directly to the Java method definition (F4 / Ctrl+Click).
- **Code to Tool:** Click the Line Marker in your Java code to open the API in RestPilot.

---

## 📸 Screenshots

*(Place your screenshots or GIFs here. I recommend adding a GIF showing the "Code -> Click Line Marker -> Send Request" flow)*

---

## 🚀 Installation

1.  Open **IntelliJ IDEA**.
2.  Go to **Settings/Preferences** > **Plugins**.
3.  Search for **"RestPilot"**.
4.  Click **Install** and restart the IDE.

---

## 🛠️ Usage Guide

1.  **Open the Panel:** Click the `RestPilot` tab on the right side of your IDE.
2.  **Auto-Discovery:** You will see a tree view of all your Spring Boot Controllers.
3.  **Send a Request:** Double-click an endpoint, or click the Run icon in your Java editor.
4.  **Manage Env:** Click "No Environment" in the toolbar to create variables like `{{host}}` or `{{token}}`.

---

## 🤝 Contributing

Contributions are welcome! If you have ideas for new features (like GraphQL support or WebSocket), feel free to open an issue or submit a Pull Request.

1.  Fork the Project
2.  Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the Branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

---

## 👨‍💻 Author

**Phil Zhang**

- 🌍 Website: [Home Page](https://phil-the-guy.netlify.app/)
- 📧 Email: bigphil.zhang@qq.com

---

<p align="center">
  <i>Made with ❤️ for the Java Community.</i>
</p>