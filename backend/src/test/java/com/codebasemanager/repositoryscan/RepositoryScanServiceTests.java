package com.codebasemanager.repositoryscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codebasemanager.repositoryscan.dto.BranchComparisonResponse;
import com.codebasemanager.repositoryscan.dto.FileChangeResponse;
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
class RepositoryScanServiceTests {

	@Mock
	private JdbcTemplate jdbcTemplate;

	private RepositoryScanService repositoryScanService;

	@BeforeEach
	void setUp() {
		repositoryScanService = new RepositoryScanService(jdbcTemplate);
	}

	@Test
	void getBranchComparisonRejectsMissingBranch() {
		when(jdbcTemplate.queryForObject(
				contains("COUNT(*) FROM branches"),
				eq(Integer.class),
				eq(10L),
				eq(99L))).thenReturn(0);

		assertThatThrownBy(() -> repositoryScanService.getBranchComparison(10L, 99L))
				.isInstanceOf(RepositoryResourceNotFoundException.class)
				.hasMessage("Branch not found for repository: 99");
	}

	@Test
	void getBranchComparisonRejectsNullBranchCount() {
		when(jdbcTemplate.queryForObject(
				contains("COUNT(*) FROM branches"),
				eq(Integer.class),
				eq(10L),
				eq(99L))).thenReturn(null);

		assertThatThrownBy(() -> repositoryScanService.getBranchComparison(10L, 99L))
				.isInstanceOf(RepositoryResourceNotFoundException.class)
				.hasMessage("Branch not found for repository: 99");
	}

	@Test
	@SuppressWarnings("unchecked")
	void getBranchComparisonRejectsBranchWithoutCompletedScan() {
		when(jdbcTemplate.queryForObject(
				contains("COUNT(*) FROM branches"),
				eq(Integer.class),
				eq(10L),
				eq(20L))).thenReturn(1);
		when(jdbcTemplate.query(
				contains("FROM scan_runs"),
				any(ResultSetExtractor.class),
				eq(10L),
				eq(20L))).thenAnswer(invocation -> {
			ResultSetExtractor<?> extractor = invocation.getArgument(1);
			ResultSet resultSet = mock(ResultSet.class);
			when(resultSet.next()).thenReturn(false);
			return extractor.extractData(resultSet);
		});

		assertThatThrownBy(() -> repositoryScanService.getBranchComparison(10L, 20L))
				.isInstanceOf(RepositoryScanException.class)
				.hasMessage("No completed scan found for branch: 20");
	}

	@Test
	@SuppressWarnings("unchecked")
	void getBranchComparisonReturnsCompletedScanWithoutChanges() {
		OffsetDateTime scannedAt = OffsetDateTime.parse("2026-07-08T14:00:00Z");
		givenExistingBranch(10L, 20L);
		givenCompletedScan(10L, 20L, 44L, "feature/current", "main", "abc123", "def456", scannedAt);
		when(jdbcTemplate.query(
				contains("FROM file_changes"),
				any(RowMapper.class),
				eq(44L))).thenReturn(List.of());

		BranchComparisonResponse response = repositoryScanService.getBranchComparison(10L, 20L);

		assertThat(response.repositoryId()).isEqualTo(10L);
		assertThat(response.branchId()).isEqualTo(20L);
		assertThat(response.scanRunId()).isEqualTo(44L);
		assertThat(response.branch()).isEqualTo("feature/current");
		assertThat(response.defaultBranch()).isEqualTo("main");
		assertThat(response.baseCommitSha()).isEqualTo("abc123");
		assertThat(response.headCommitSha()).isEqualTo("def456");
		assertThat(response.scannedAt()).isEqualTo(scannedAt);
		assertThat(response.changedFileCount()).isZero();
		assertThat(response.additions()).isZero();
		assertThat(response.deletions()).isZero();
		assertThat(response.changes()).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void getBranchComparisonReturnsChangedFilesAndTotals() {
		givenExistingBranch(10L, 20L);
		givenCompletedScan(10L, 20L, 44L, "feature/current", "main", "abc123", "def456", null);
		when(jdbcTemplate.query(
				contains("FROM file_changes"),
				any(RowMapper.class),
				eq(44L))).thenReturn(List.of(
				new FileChangeResponse("src/New.java", null, "added", 15, 0),
				new FileChangeResponse("src/Old.java", null, "deleted", 0, 8),
				new FileChangeResponse("src/Renamed.java", "src/Original.java", "renamed", 3, 2)));

		BranchComparisonResponse response = repositoryScanService.getBranchComparison(10L, 20L);

		assertThat(response.changedFileCount()).isEqualTo(3);
		assertThat(response.additions()).isEqualTo(18);
		assertThat(response.deletions()).isEqualTo(10);
		assertThat(response.changes())
				.extracting(FileChangeResponse::path)
				.containsExactly("src/New.java", "src/Old.java", "src/Renamed.java");
		assertThat(response.changes().get(2).oldPath()).isEqualTo("src/Original.java");
	}

	@Test
	@SuppressWarnings("unchecked")
	void getBranchComparisonAllowsMissingDefaultBranchAndBaseCommit() {
		givenExistingBranch(10L, 20L);
		givenCompletedScan(10L, 20L, 44L, "main", null, null, "def456", null);
		when(jdbcTemplate.query(
				contains("FROM file_changes"),
				any(RowMapper.class),
				eq(44L))).thenReturn(List.of());

		BranchComparisonResponse response = repositoryScanService.getBranchComparison(10L, 20L);

		assertThat(response.branch()).isEqualTo("main");
		assertThat(response.defaultBranch()).isNull();
		assertThat(response.baseCommitSha()).isNull();
		assertThat(response.headCommitSha()).isEqualTo("def456");
		assertThat(response.changedFileCount()).isZero();
	}

	private void givenExistingBranch(long repositoryId, long branchId) {
		when(jdbcTemplate.queryForObject(
				contains("COUNT(*) FROM branches"),
				eq(Integer.class),
				eq(repositoryId),
				eq(branchId))).thenReturn(1);
	}

	@SuppressWarnings("unchecked")
	private void givenCompletedScan(
			long repositoryId,
			long branchId,
			long scanRunId,
			String branch,
			String defaultBranch,
			String baseCommitSha,
			String headCommitSha,
			OffsetDateTime scannedAt) {
		when(jdbcTemplate.query(
				contains("FROM scan_runs"),
				any(ResultSetExtractor.class),
				eq(repositoryId),
				eq(branchId))).thenAnswer(invocation -> {
			ResultSetExtractor<?> extractor = invocation.getArgument(1);
			ResultSet resultSet = mock(ResultSet.class);
			when(resultSet.next()).thenReturn(true);
			when(resultSet.getLong("scan_run_id")).thenReturn(scanRunId);
			when(resultSet.getString("branch")).thenReturn(branch);
			when(resultSet.getString("default_branch")).thenReturn(defaultBranch);
			when(resultSet.getString("base_commit_sha")).thenReturn(baseCommitSha);
			when(resultSet.getString("head_commit_sha")).thenReturn(headCommitSha);
			when(resultSet.getObject("scanned_at", OffsetDateTime.class)).thenReturn(scannedAt);
			return extractor.extractData(resultSet);
		});
	}
}
