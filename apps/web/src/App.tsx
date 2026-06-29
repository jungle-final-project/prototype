import { Navigate, Route, Routes } from 'react-router-dom';
import { LoginPage, SignupPage } from './features/auth/AuthPages';
import { RequireAdmin } from './features/auth/RequireAdmin';
import { SelfQuotePage } from './features/parts/PartsPages';
import { BuildResultPage, ChangePartPage, HomePage, MyQuotesPage, RequirementPage } from './features/quote/QuotePages';
import { SupportNewPage, SupportTicketPage } from './features/support/SupportPages';
import { AdminDashboardPage, AdminLoadTestsPage, AdminPartsPage, AdminPriceJobsPage, AdminTicketDetailPage, AdminTicketsPage, AgentSessionAdminPage, RagEvidenceAdminPage, ToolInvocationAdminPage } from './features/admin/AdminPages';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/requirements/new" element={<RequirementPage />} />
      <Route path="/builds/:buildId" element={<BuildResultPage />} />
      <Route path="/self-quote" element={<SelfQuotePage />} />
      <Route path="/builds/:buildId/change-part" element={<ChangePartPage />} />
      <Route path="/my/quotes" element={<MyQuotesPage />} />
      <Route path="/support/new" element={<SupportNewPage />} />
      <Route path="/support/:ticketId" element={<SupportTicketPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route path="/admin" element={<RequireAdmin><AdminDashboardPage /></RequireAdmin>} />
      <Route path="/admin/agent-sessions/:id" element={<RequireAdmin><AgentSessionAdminPage /></RequireAdmin>} />
      <Route path="/admin/tool-invocations/:id" element={<RequireAdmin><ToolInvocationAdminPage /></RequireAdmin>} />
      <Route path="/admin/rag-evidence/:id" element={<RequireAdmin><RagEvidenceAdminPage /></RequireAdmin>} />
      <Route path="/admin/parts" element={<RequireAdmin><AdminPartsPage /></RequireAdmin>} />
      <Route path="/admin/price-jobs" element={<RequireAdmin><AdminPriceJobsPage /></RequireAdmin>} />
      <Route path="/admin/load-tests" element={<RequireAdmin><AdminLoadTestsPage /></RequireAdmin>} />
      <Route path="/admin/as-tickets" element={<RequireAdmin><AdminTicketsPage /></RequireAdmin>} />
      <Route path="/admin/as-tickets/:ticketId" element={<RequireAdmin><AdminTicketDetailPage /></RequireAdmin>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
