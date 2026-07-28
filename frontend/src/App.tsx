import './App.css';
import { ImportPanel } from './components/ImportPanel';
import { RepositoryViews } from './components/RepositoryViews';
import { TopNavigation } from './components/TopNavigation';
import { RepositoryProvider } from './context/RepositoryContext';

function App() {
  return (
    <RepositoryProvider>
      <div className="app-shell">
        <TopNavigation />
        <ImportPanel />
        <main className="workspace">
          <RepositoryViews />
        </main>
      </div>
    </RepositoryProvider>
  );
}

export default App;
