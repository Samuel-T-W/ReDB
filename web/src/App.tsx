import { Navigate, Route, Routes } from "react-router-dom";
import IterationPage from "./components/IterationPage";
import PlannedWork from "./components/PlannedWork";
import { LATEST_IMPLEMENTED } from "./data/iterations";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to={`/iteration/${LATEST_IMPLEMENTED.id}`} replace />} />
      <Route path="/iteration/:id" element={<IterationPage />} />
      <Route path="/planned" element={<PlannedWork />} />
      <Route path="*" element={<Navigate to={`/iteration/${LATEST_IMPLEMENTED.id}`} replace />} />
    </Routes>
  );
}
