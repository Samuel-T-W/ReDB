import { Navigate, Route, Routes } from "react-router-dom";
import Home from "./components/Home";
import IterationPage from "./components/IterationPage";

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/iteration/:id" element={<IterationPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
