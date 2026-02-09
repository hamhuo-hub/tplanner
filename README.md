# Travel Planner (tplanner)

An offline-first, timeline-based web application for planning travel itineraries and managing schedules. Inspired by university timetable layouts, it offers a clear, horizontal visualization of your schedule with robust conflict detection and easy event management.

## 🚀 Features

- **Interactive Timeline**:
  - Horizontal scrollable view covering a dynamic range (default view: 2 months).
  - Pagination controls to navigate through time (Previous/Next 2 months, Jump to Today).
  - Visual "breathing" indicators for time conflicts.

- **Event Management**:
  - **Create**: Click anywhere on the timeline to add an event.
  - **Edit**: Drag and drop support (planned) or click to edit details.
  - **Customize**: Color-coded events (0-6 theme palette) for better organization.
  - **Details**: Add titles, specific start/end times, and notes.

- **Conflict Detection**:
  - Automatic detection of overlapping events.
  - A comprehensive "Clash Banner" at the top alerts you to schedule conflicts.
  - Click on a conflict alert to instantly jump to the problematic time range.

- **Data Persistence**:
  - **Local Storage**: Automatically saves all your data to a local `data.json` file via the included backend server.
  - **Import/Export**: Easily export your schedule to a JSON file or import existing data.

## 🛠️ Tech Stack

- **Frontend**: [React 18](https://reactjs.org/), [Vite](https://vitejs.dev/)
- **Styling**: [Tailwind CSS](https://tailwindcss.com/), [Lucide React](https://lucide.dev/) (Icons)
- **Backend**: [Express.js](https://expressjs.com/) (Lightweight local server for file I/O)
- **Utilities**: [date-fns](https://date-fns.org/)

## 📦 Installation

Prerequisites: [Node.js](https://nodejs.org/) (v14+ recommended).

1.  Clone the repository:
    ```bash
    git clone https://github.com/yourusername/tplanner.git
    cd tplanner
    ```

2.  Install dependencies:
    ```bash
    npm install
    ```

## 🚦 Usage

To start the application, run the development command. This will simultaneously launch the **Frontend** (Vite) and the **Backend** (Express server).

```bash
npm run dev
```

-   **Frontend**: Open [http://localhost:5173](http://localhost:5173) in your browser.
-   **Backend API**: Running on [http://localhost:3001](http://localhost:3001).

> **Note**: The backend server is required for saving your changes to `data.json`.

## 📂 Project Structure

```text
tplanner/
├── src/
│   ├── components/       # UI Components (Timeline, modals, etc.)
│   ├── utils/            # Helper functions (date calculations, etc.)
│   ├── App.jsx           # Main application logic
│   └── main.jsx          # Entry point
├── server.js             # Local Express server for persistence
├── data.json             # Data store for events (auto-generated)
├── package.json          # Project dependencies and scripts
└── README.md             # Project documentation
```

## 🤝 Contributing

1.  Fork the project
2.  Create your feature branch (`git checkout -b feature/AmazingFeature`)
3.  Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4.  Push to the branch (`git push origin feature/AmazingFeature`)
5.  Open a Pull Request

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
