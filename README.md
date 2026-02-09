# Travel Planner

A React-based timeline application for planning travel events.

## Features
- **Timeline View**: Scrollable 2-month view with pagination (Prev/Next 2 months).
- **Event Management**: Create, edit, and delete events with specific times and notes.
- **Conflict Management**: 
  - Automatic detection of overlapping events.
  - "Breathing" red highlight for conflicting time ranges.
  - Jump-to-conflict functionality from the alert banner.
- **Persistence**: 
  - Events are automatically saved to `data.json` in the project root.
  - No database required; uses a local Node.js server.

## How to Run

### Prerequisites
- Node.js installed.

### Setup
1. Install dependencies:
   ```bash
   npm install
   ```

### Start Application
Run the following command to start both the Frontend and the Backend server simultaneously:

```bash
npm run dev
```

- **Frontend**: http://localhost:5173
- **Backend**: http://localhost:3001

> **Note**: Do not run `vite` or `node server.js` separately unless debugging. `npm run dev` handles both using `concurrently`.

## Project Structure
- `src/`: React source code.
- `server.js`: Simple Express server for file-based persistence.
- `data.json`: Stores all event data (created automatically).
