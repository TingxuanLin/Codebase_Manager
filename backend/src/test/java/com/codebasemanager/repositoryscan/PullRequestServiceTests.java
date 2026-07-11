package com.codebasemanager.repositoryscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codebasemanager.repositoryscan.dto.PullRequestDiffResponse;
import com.codebasemanager.repositoryscan.dto.PullRequestFileChangeResponse;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PullRequestServiceTests {

	@Mock
	private JdbcTemplate jdbcTemplate;

	private PullRequestService pullRequestService;

	@BeforeEach
	void setUp() {
		pullRequestService = new PullRequestService(jdbcTemplate, mock(TransactionTemplate.class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void getStoredPullRequestDiffReturnsStoredChangesAndTotals() {
		OffsetDateTime diffFetchedAt = OffsetDateTime.parse("2026-07-11T15:30:00Z");
		givenExistingRepository(10L);
		givenStoredPullRequest(10L, 7, 99L, diffFetchedAt);
		when(jdbcTemplate.query(
				contains("FROM pull_request_file_changes"),
				any(RowMapper.class),
				eq(99L))).thenReturn(List.of(
				new PullRequestFileChangeResponse("src/New.java", null, "added", 12, 0, 12, "@@ patch", "https://example.com/blob", "https://example.com/raw"),
				new PullRequestFileChangeResponse("src/Renamed.java", "src/Old.java", "renamed", 3, 2, 5, null, null, null)));

		PullRequestDiffResponse response = pullRequestService.getStoredPullRequestDiff(10L, 7);

		assertThat(response.repositoryId()).isEqualTo(10L);
		assertThat(response.number()).isEqualTo(7);
		assertThat(response.title()).isEqualTo("Improve parser");
		assertThat(response.baseBranch()).isEqualTo("main");
		assertThat(response.headBranch()).isEqualTo("feature/parser");
		assertThat(response.baseSha()).isEqualTo("base123");
		assertThat(response.headSha()).isEqualTo("head456");
		assertThat(response.diffFetchedAt()).isEqualTo(diffFetchedAt);
		assertThat(response.changedFileCount()).isEqualTo(2);
		assertThat(response.additions()).isEqualTo(15);
		assertThat(response.deletions()).isEqualTo(2);
		assertThat(response.changes())
				.extracting(PullRequestFileChangeResponse::path)
				.containsExactly("src/New.java", "src/Renamed.java");
		assertThat(response.changes().get(1).oldPath()).isEqualTo("src/Old.java");
	}

	@Test
	void getStoredPullRequestDiffRejectsMissingRepository() {
		when(jdbcTemplate.queryForObject(
				contains("COUNT(*) FROM repositories"),
				eq(Integer.class),
				eq(10L))).thenReturn(0);

		assertThatThrownBy(() -> pullRequestService.getStoredPullRequestDiff(10L, 7))
				.isInstanceOf(RepositoryResourceNotFoundException.class)
				.hasMessage("Repository not found: 10");
	}

	@Test
	void getStoredPullRequestDiffRejectsMissingPullRequest() {
		givenExistingRepository(10L);
		when(jdbcTemplate.queryForObject(
				contains("FROM pull_requests"),
				any(RowMapper.class),
				eq(10L),
				eq(7))).thenThrow(new EmptyResultDataAccessException(1));

		assertThatThrownBy(() -> pullRequestService.getStoredPullRequestDiff(10L, 7))
				.isInstanceOf(RepositoryResourceNotFoundException.class)
				.hasMessage("Pull request not found for repository: 7");
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
			when(resultSet.getLong("id")).thenReturn(pullRequestId);
			when(resultSet.getInt("github_pr_number")).thenReturn(pullRequestNumber);
			when(resultSet.getString("title")).thenReturn("Improve parser");
			when(resultSet.getString("state")).thenReturn("open");
			when(resultSet.getString("base_branch")).thenReturn("main");
			when(resultSet.getString("head_branch")).thenReturn("feature/parser");
			when(resultSet.getString("base_sha")).thenReturn("base123");
			when(resultSet.getString("head_sha")).thenReturn("head456");
			when(resultSet.getObject("diff_fetched_at", OffsetDateTime.class)).thenReturn(diffFetchedAt);
			return mapper.mapRow(resultSet, 0);
		});
	}
}
