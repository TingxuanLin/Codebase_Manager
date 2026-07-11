package com.codebasemanager.repositoryscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codebasemanager.repositoryscan.dto.PullRequestRiskFindingResponse;
import com.codebasemanager.repositoryscan.dto.PullRequestRiskResponse;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class PullRequestRiskServiceTests {

	@Mock
	private JdbcTemplate jdbcTemplate;

	private PullRequestRiskService pullRequestRiskService;

	@BeforeEach
	void setUp() {
		pullRequestRiskService = new PullRequestRiskService(jdbcTemplate);
	}

	@Test
	@SuppressWarnings("unchecked")
	void analyzePullRequestRiskScoresAndStoresFindings() {
		OffsetDateTime diffFetchedAt = OffsetDateTime.parse("2026-07-11T15:30:00Z");
		OffsetDateTime generatedAt = OffsetDateTime.parse("2026-07-11T16:00:00Z");
		givenExistingRepository(10L);
		givenStoredPullRequest(10L, 7, 99L, diffFetchedAt);
		when(jdbcTemplate.query(
				contains("FROM pull_request_file_changes"),
				any(RowMapper.class),
				eq(99L))).thenAnswer(invocation -> {
			RowMapper<?> mapper = invocation.getArgument(1);
			return List.of(
					mapFileChange(mapper, "package.json", "modified", 130, 120),
					mapFileChange(mapper, "src/auth/LoginController.java", "modified", 80, 20),
					mapFileChange(mapper, "src/OldService.java", "removed", 0, 10),
					mapFileChange(mapper, "src/A.java", "modified", 1, 0),
					mapFileChange(mapper, "src/B.java", "modified", 1, 0),
					mapFileChange(mapper, "src/C.java", "modified", 1, 0),
					mapFileChange(mapper, "src/D.java", "modified", 1, 0));
		});
		when(jdbcTemplate.queryForObject(
				contains("FROM api_routes"),
				eq(Integer.class),
				eq(10L),
				eq(10L),
				eq("feature/risk"),
				any(String.class))).thenAnswer(invocation ->
				"src/auth/LoginController.java".equals(invocation.getArgument(5)) ? 2 : 0);
		when(jdbcTemplate.query(
				contains("SELECT sf.loc"),
				any(ResultSetExtractor.class),
				eq(10L),
				eq(10L),
				eq("feature/risk"),
				any(String.class))).thenReturn(null);
		when(jdbcTemplate.queryForObject(
				contains("COUNT(d.id)"),
				eq(Integer.class),
				eq(10L),
				eq(10L),
				eq("feature/risk"),
				any(String.class))).thenReturn(0);
		when(jdbcTemplate.queryForObject(
				contains("INSERT INTO pull_request_risk_analyses"),
				eq(Long.class),
				eq(99L),
				any(Integer.class),
				any(String.class),
				eq("rules-v1"))).thenReturn(123L);
		when(jdbcTemplate.queryForObject(
				eq("SELECT generated_at FROM pull_request_risk_analyses WHERE id = ?"),
				eq(OffsetDateTime.class),
				eq(123L))).thenReturn(generatedAt);

		PullRequestRiskResponse response = pullRequestRiskService.analyzePullRequestRisk(10L, 7);

		assertThat(response.repositoryId()).isEqualTo(10L);
		assertThat(response.pullRequestNumber()).isEqualTo(7);
		assertThat(response.score()).isEqualTo(78);
		assertThat(response.severity()).isEqualTo("high");
		assertThat(response.changedFileCount()).isEqualTo(7);
		assertThat(response.additions()).isEqualTo(214);
		assertThat(response.deletions()).isEqualTo(150);
		assertThat(response.generatedAt()).isEqualTo(generatedAt);
		assertThat(response.findings())
				.extracting(PullRequestRiskFindingResponse::category)
				.contains("dependency_change", "api_surface", "sensitive_path", "deleted_files", "code_churn", "change_volume");
	}

	@Test
	void analyzePullRequestRiskRejectsMissingRepository() {
		when(jdbcTemplate.queryForObject(
				contains("COUNT(*) FROM repositories"),
				eq(Integer.class),
				eq(10L))).thenReturn(0);

		assertThatThrownBy(() -> pullRequestRiskService.analyzePullRequestRisk(10L, 7))
				.isInstanceOf(RepositoryResourceNotFoundException.class)
				.hasMessage("Repository not found: 10");
	}

	private void givenExistingRepository(long repositoryId) {
		when(jdbcTemplate.queryForObject(
				contains("COUNT(*) FROM repositories"),
				eq(Integer.class),
				eq(repositoryId))).thenReturn(1);
	}

	@SuppressWarnings("unchecked")
	private void givenStoredPullRequest(long repositoryId, int pullRequestNumber, long pullRequestId, OffsetDateTime diffFetchedAt) {
		when(jdbcTemplate.queryForObject(
				contains("FROM pull_requests"),
				any(RowMapper.class),
				eq(repositoryId),
				eq(pullRequestNumber))).thenAnswer(invocation -> {
			RowMapper<?> mapper = invocation.getArgument(1);
			ResultSet resultSet = mock(ResultSet.class);
			when(resultSet.getLong("repository_id")).thenReturn(repositoryId);
			when(resultSet.getLong("id")).thenReturn(pullRequestId);
			when(resultSet.getInt("github_pr_number")).thenReturn(pullRequestNumber);
			when(resultSet.getString("title")).thenReturn("Improve risk analysis");
			when(resultSet.getString("state")).thenReturn("open");
			when(resultSet.getString("base_branch")).thenReturn("main");
			when(resultSet.getString("head_branch")).thenReturn("feature/risk");
			when(resultSet.getString("base_sha")).thenReturn("base123");
			when(resultSet.getString("head_sha")).thenReturn("head456");
			when(resultSet.getObject("diff_fetched_at", OffsetDateTime.class)).thenReturn(diffFetchedAt);
			return mapper.mapRow(resultSet, 0);
		});
	}

	private Object mapFileChange(
			RowMapper<?> mapper,
			String path,
			String changeType,
			int additions,
			int deletions) throws java.sql.SQLException {
		ResultSet resultSet = mock(ResultSet.class);
		when(resultSet.getString("path")).thenReturn(path);
		when(resultSet.getString("old_path")).thenReturn(null);
		when(resultSet.getString("change_type")).thenReturn(changeType);
		when(resultSet.getInt("additions")).thenReturn(additions);
		when(resultSet.getInt("deletions")).thenReturn(deletions);
		when(resultSet.getInt("changes")).thenReturn(additions + deletions);
		return mapper.mapRow(resultSet, 0);
	}
}
