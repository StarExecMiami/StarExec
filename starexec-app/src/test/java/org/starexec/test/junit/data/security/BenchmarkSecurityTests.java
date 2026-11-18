package org.starexec.test.junit.data.security;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.starexec.data.database.*;
import org.starexec.data.security.BenchmarkSecurity;
import org.starexec.data.security.GeneralSecurity;
import org.starexec.data.security.ProcessorSecurity;
import org.starexec.data.security.ValidatorStatusCode;
import org.starexec.data.to.*;
import org.starexec.data.to.enums.ProcessorType;
import org.starexec.util.Validator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;

public class BenchmarkSecurityTests {

    @Test
    public void canUserDownloadBenchmarkGetsNullBenchTest() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(null);

            // when / then
            assertFalse(BenchmarkSecurity.canUserDownloadBenchmark(benchId, userId).isSuccess());
        }
    }

    @Test
    public void canUserDownloadBenchmarkWhenUserCantSeeBenchTest() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(mock(Benchmark.class));
            permissionsMock.when(() -> Permissions.canUserSeeBench(benchId, userId)).thenReturn(false);

            // when / then
            assertFalse(BenchmarkSecurity.canUserDownloadBenchmark(benchId, userId).isSuccess());
        }
    }

    @Test
    public void canUserDownloadBenchmarkWhenUserHasAdminPrivileges() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            int otherUserId = 3;
            Benchmark bench = mock(Benchmark.class);
            given(bench.isDownloadable()).willReturn(false);
            // Then benchmark is not the users benchmark
            given(bench.getUserId()).willReturn(otherUserId);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);
            permissionsMock.when(() -> Permissions.canUserSeeBench(benchId, userId)).thenReturn(true);
            // The user has admin privileges.
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(true);

            // when / then
            assertTrue(BenchmarkSecurity.canUserDownloadBenchmark(benchId, userId).isSuccess(),
                    "The user is an admin so should be able to download the benchmark.");
        }
    }

    @Test
    public void canUserDownloadBenchmarkWhenUserIsOwner() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            Benchmark bench = mock(Benchmark.class);
            given(bench.isDownloadable()).willReturn(false);
            // Then benchmark is not the users benchmark
            given(bench.getUserId()).willReturn(userId);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);
            permissionsMock.when(() -> Permissions.canUserSeeBench(benchId, userId)).thenReturn(true);
            // The user has admin privileges.
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(false);

            // when / then
            assertTrue(BenchmarkSecurity.canUserDownloadBenchmark(benchId, userId).isSuccess(),
                    "The user is owner so should be able to download the benchmark.");
        }
    }

    @Test
    public void canUserDownloadBenchmarkWhenBenchmarkIsDownloadable() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            int otherUserId = 3;
            Benchmark bench = mock(Benchmark.class);
            given(bench.isDownloadable()).willReturn(true);
            // Then benchmark is not the users benchmark
            given(bench.getUserId()).willReturn(otherUserId);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);
            permissionsMock.when(() -> Permissions.canUserSeeBench(benchId, userId)).thenReturn(true);
            // The user has admin privileges.
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(false);

            // when / then
            assertTrue(BenchmarkSecurity.canUserDownloadBenchmark(benchId, userId).isSuccess(),
                    "The benchmark is downloadable so user should be able to download.");
        }
    }
    @Test
    public void canUserDownloadBenchmarkWhenUserIsNotAdminBenchIsNotDownloadableAndUserIsNotOwner() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            int otherUserId = 3;
            Benchmark bench = mock(Benchmark.class);
            given(bench.isDownloadable()).willReturn(false);
            // Then benchmark is not the users benchmark
            given(bench.getUserId()).willReturn(otherUserId);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);
            permissionsMock.when(() -> Permissions.canUserSeeBench(benchId, userId)).thenReturn(true);
            // The user has admin privileges.
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(false);

            // when / then
            assertFalse(BenchmarkSecurity.canUserDownloadBenchmark(benchId, userId).isSuccess(),
                    "The user should not be able to download the benchmark.");
        }
    }

    @Test
    public void canUserGetAnonymousLinkIfUserOwnsBenchmarkOrIsAdmin() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            Benchmark bench = mock(Benchmark.class);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(true);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserGetAnonymousLink(benchId, userId)).thenCallRealMethod();

            // when / then
            assertTrue(BenchmarkSecurity.canUserGetAnonymousLink(benchId, userId).isSuccess(),
                    "Admins and owners should be able to get the anon link.");
        }
    }

    @Test
    public void canUserGetAnonymousLinkIfUserDoesNotOwnBenchmarkOrIsAdmin() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            Benchmark bench = mock(Benchmark.class);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(false);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserGetAnonymousLink(benchId, userId)).thenCallRealMethod();

            // when / then
            assertFalse(BenchmarkSecurity.canUserGetAnonymousLink(benchId, userId).isSuccess(),
                    "Only admins and owners should be able to get the anon link.");
        }
    }

    @Test
    public void canUserSeeBenchmarkContentsWhenBenchIsNull() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(null);
            // when / then
            assertFalse(BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId).isSuccess(), "Should be false for null benchmark.");
        }
    }

    @Test
    public void userCanSeeBenchmarkContentsIfUserIsOwner() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            Benchmark bench = mock(Benchmark.class);
            // User owns this benchmark.
            given(bench.getUserId()).willReturn(userId);
            // user is not admin
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(false);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);

            // when / then
            assertTrue(BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId).isSuccess(),
                    "Should be true since user owns the benchmark");
        }
    }

    @Test
    public void userCanSeeBenchmarkContentsIfUserIsAdmin() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            int otherUserId = 3;
            Benchmark bench = mock(Benchmark.class);
            // User does not own this benchmark
            given(bench.getUserId()).willReturn(otherUserId);
            // user is admin
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);

            // when / then
            assertTrue(BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId).isSuccess(), "Should be true since user is admin");
        }
    }

    @Test
    public void userCanSeeBenchmarkContentsIfBenchIsDownloadableAndUserCanSeeBench() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            int otherUserId = 3;
            Benchmark bench = mock(Benchmark.class);
            // User does not own this benchmark
            given(bench.getUserId()).willReturn(otherUserId);
            // bench is downloadable
            given(bench.isDownloadable()).willReturn(true);
            given(bench.getId()).willReturn(benchId);
            // user is not admin.
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(false);
            permissionsMock.when(() -> Permissions.canUserSeeBench(benchId, userId)).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);

            // when / then
            assertTrue(BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId).isSuccess(),
                    "Should be true since bench is downloadable and user can see the benchmark.");
        }
    }

    @Test
    public void userCannotSeeBenchmarkContentsIfBenchIsNotDownloadable() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            int otherUserId = 3;
            Benchmark bench = mock(Benchmark.class);
            // User does not own this benchmark
            given(bench.getUserId()).willReturn(otherUserId);
            // bench is downloadable
            given(bench.isDownloadable()).willReturn(false);
            // user is not admin.
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(false);
            permissionsMock.when(() -> Permissions.canUserSeeBench(benchId, userId)).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);

            // when / then
            assertFalse(BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId).isSuccess(),
                    "Should be false since bench is not downloadable.");
        }
    }

    @Test
    public void userCannotSeeBenchmarkContentsIfUserCannotSeeBench() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            int otherUserId = 3;
            Benchmark bench = mock(Benchmark.class);
            // User does not own this benchmark
            given(bench.getUserId()).willReturn(otherUserId);
            // bench is downloadable
            given(bench.isDownloadable()).willReturn(true);
            // user is not admin.
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(userId)).thenReturn(false);
            permissionsMock.when(() -> Permissions.canUserSeeBench(benchId, userId)).thenReturn(false);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);

            // when / then
            assertFalse(BenchmarkSecurity.canUserSeeBenchmarkContents(benchId, userId).isSuccess(),
                    "Should be true false since user cant see bench.");
        }
    }

    @Test
    public void userCannotDeleteOrRecycleBenchIfBenchCannotBeFound() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(benchId, false)).thenReturn(null);

            // when / then
            assertFalse(BenchmarkSecurity.canUserDeleteBench(benchId, userId).isSuccess(),
                    "Benchmark cannot be found so should not be deletable.");
            assertFalse(BenchmarkSecurity.canUserRecycleBench(benchId, userId).isSuccess(),
                    "Benchmark cannot be found so should not be recyclable.");
        }
    }

    @Test
    public void userCannotDeleteOrRecycleBenchIfTheyDoNotOwnBenchOrAreAdmin() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            Benchmark bench = mock(Benchmark.class);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(benchId, false)).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(false);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserDeleteBench(benchId, userId)).thenCallRealMethod();
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserRecycleBench(benchId, userId)).thenCallRealMethod();

            // when / then
            assertFalse(BenchmarkSecurity.canUserDeleteBench(benchId, userId).isSuccess(),
                    "User does not own bench and is not admin so should not be deletable.");
            assertFalse(BenchmarkSecurity.canUserRecycleBench(benchId, userId).isSuccess(),
                    "User does not own bench and is not admin so should not be recyclable.");
        }
    }

    @Test
    public void userCanDeleteOrRecycleBenchIfTheyOwnBenchOrAreAdmin() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class)) {
            // given
            int benchId = 1;
            int userId = 2;
            Benchmark bench = mock(Benchmark.class);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(benchId, false)).thenReturn(bench);
            benchmarksMock.when(() -> Benchmarks.get(benchId)).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(true);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserDeleteBench(benchId, userId)).thenCallRealMethod();
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserRecycleBench(benchId, userId)).thenCallRealMethod();

            // when / then
            assertTrue(BenchmarkSecurity.canUserDeleteBench(benchId, userId).isSuccess(),
                    "User owns bench or is admin so should be deletable.");
            assertTrue(BenchmarkSecurity.canUserRecycleBench(benchId, userId).isSuccess(),
                    "User owns bench or is admin so should be recyclable.");
        }
    }

    @Test
    public void userCantRestoreBenchmarkIfBenchmarkCantBeFound() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class)) {
            // given
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(null);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserRestoreBenchmark(1, 2);

            // then
            assertFalse(status.isSuccess(), "User cant restore benchmark if benchmark was not found.");
        }
    }

    @Test
    public void userCantRestoreBenchmarkIfBenchmarkNotRecycled() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class)) {
            // given
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(mock(Benchmark.class));
            benchmarksMock.when(() -> Benchmarks.isBenchmarkRecycled(anyInt())).thenReturn(false);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserRestoreBenchmark(1, 2);

            // then
            assertFalse(status.isSuccess(), "User cant restore benchmark if benchmark has not been recycled.");
        }
    }

    @Test
    public void userCantRestoreBenchmarkIfUserDoesNotOwnBenchmarkAndIsNotAdmin() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class)) {
            // given
            int userId = 2;
            Benchmark b = mock(Benchmark.class);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(b);
            benchmarksMock.when(() -> Benchmarks.isBenchmarkRecycled(anyInt())).thenReturn(true);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(b, userId)).thenReturn(false);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserRestoreBenchmark(1, userId)).thenCallRealMethod();

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserRestoreBenchmark(1, userId);

            // then
            assertFalse(status.isSuccess(), "User cant restore benchmark if user does not own benchmark and is not admin.");
        }
    }

    @Test
    public void userCanRestoreBenchmarkIfUserOwnsBenchmarkOrIsAdmin() {
        try (MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class)) {
            // given
            int userId = 2;
            Benchmark b = mock(Benchmark.class);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(b);
            benchmarksMock.when(() -> Benchmarks.isBenchmarkRecycled(anyInt())).thenReturn(true);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(b, userId)).thenReturn(true);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserRestoreBenchmark(1, userId)).thenCallRealMethod();

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserRestoreBenchmark(1, userId);

            // then
            assertTrue(status.isSuccess(), "User can restore benchmark if user owns benchmark or is admin.");
        }
    }

    @Test
    public void userCantEditBenchmarkIfNewNameIsNotValid() {
        try (MockedStatic<Validator> validatorMock = Mockito.mockStatic(Validator.class)) {
            // given
            validatorMock.when(() -> Validator.isValidBenchName(anyString())).thenReturn(false);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, 3);

            // then
            assertFalse(status.isSuccess(), "Should not be able to edit benchmark if new name is invalid.");
        }
    }

    @Test
    public void userCantEditBenchmarkIfDescriptionIsNotValid() {
        try (MockedStatic<Validator> validatorMock = Mockito.mockStatic(Validator.class)) {
            // given
            validatorMock.when(() -> Validator.isValidBenchName(anyString())).thenReturn(true);
            validatorMock.when(() -> Validator.isValidPrimDescription(anyString())).thenReturn(false);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, 3);

            // then
            assertFalse(status.isSuccess(), "Should not be able to edit benchmark if description is invalid.");
        }
    }

    @Test
    public void userCantEditBenchmarkIfBenchmarkCantBeFound() {
        try (MockedStatic<Validator> validatorMock = Mockito.mockStatic(Validator.class);
             MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class)) {
            // given
            validatorMock.when(() -> Validator.isValidBenchName(anyString())).thenReturn(true);
            validatorMock.when(() -> Validator.isValidPrimDescription(anyString())).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(null);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, 3);

            // then
            assertFalse(status.isSuccess(), "Should not be able to edit benchmark if benchmark cannot be found.");
        }
    }

    @Test
    public void userCantEditBenchmarkIfUserIsNotOwnerOrAdmin() {
        try (MockedStatic<Validator> validatorMock = Mockito.mockStatic(Validator.class);
             MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class)) {
            // given
            final int userId = 3;
            Benchmark bench = mock(Benchmark.class);
            validatorMock.when(() -> Validator.isValidBenchName(anyString())).thenReturn(true);
            validatorMock.when(() -> Validator.isValidPrimDescription(anyString())).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(false);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId)).thenCallRealMethod();

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId);

            // then
            assertFalse(status.isSuccess(), "Should not be able to edit benchmark if user is not owner or admin.");
        }
    }

    @Test
    public void userCantEditBenchmarkIfBenchmarkIsDeleted() {
        try (MockedStatic<Validator> validatorMock = Mockito.mockStatic(Validator.class);
             MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class)) {
            // given
            final int userId = 3;
            Benchmark bench = mock(Benchmark.class);
            validatorMock.when(() -> Validator.isValidBenchName(anyString())).thenReturn(true);
            validatorMock.when(() -> Validator.isValidPrimDescription(anyString())).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.isBenchmarkDeleted(anyInt())).thenReturn(true);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId)).thenCallRealMethod();

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId);

            // then
            assertFalse(status.isSuccess(), "Should not be able to edit benchmark if it is deleted.");
        }
    }

    @Test
    public void userCantEditBenchmarkIfBenchTypeCannotBeFound() {
        try (MockedStatic<Validator> validatorMock = Mockito.mockStatic(Validator.class);
             MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class);
             MockedStatic<Processors> processorsMock = Mockito.mockStatic(Processors.class)) {
            // given
            final int userId = 3;
            Benchmark bench = mock(Benchmark.class);
            validatorMock.when(() -> Validator.isValidBenchName(anyString())).thenReturn(true);
            validatorMock.when(() -> Validator.isValidPrimDescription(anyString())).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.isBenchmarkDeleted(anyInt())).thenReturn(false);
            processorsMock.when(() -> Processors.get(anyInt())).thenReturn(null);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId)).thenCallRealMethod();

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId);

            // then
            assertFalse(status.isSuccess(), "Should not be able to edit benchmark if processor cannot be found.");
        }
    }

    @Test
    public void userCantEditBenchmarkIfProcessorIsNotBenchType() {
        try (MockedStatic<Validator> validatorMock = Mockito.mockStatic(Validator.class);
             MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class);
             MockedStatic<Processors> processorsMock = Mockito.mockStatic(Processors.class)) {
            // given
            final int userId = 3;
            Benchmark bench = mock(Benchmark.class);
            Processor proc = mock(Processor.class);
            // Processor is not BENCH type so test should fail.
            given(proc.getType()).willReturn(ProcessorType.POST);
            validatorMock.when(() -> Validator.isValidBenchName(anyString())).thenReturn(true);
            validatorMock.when(() -> Validator.isValidPrimDescription(anyString())).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.isBenchmarkDeleted(anyInt())).thenReturn(false);
            processorsMock.when(() -> Processors.get(anyInt())).thenReturn(proc);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId)).thenCallRealMethod();

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId);

            // then
            assertFalse(status.isSuccess(), "Should not be able to edit benchmark if processor is not bench type.");
        }
    }

    @Test
    public void userCantEditBenchmarkIfUserCantSeeProcessor() {
        try (MockedStatic<Validator> validatorMock = Mockito.mockStatic(Validator.class);
             MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class);
             MockedStatic<Processors> processorsMock = Mockito.mockStatic(Processors.class);
             MockedStatic<ProcessorSecurity> processorSecurityMock = Mockito.mockStatic(ProcessorSecurity.class)) {
            // given
            final int userId = 3;
            Benchmark bench = mock(Benchmark.class);
            Processor proc = mock(Processor.class);
            given(proc.getType()).willReturn(ProcessorType.BENCH);
            validatorMock.when(() -> Validator.isValidBenchName(anyString())).thenReturn(true);
            validatorMock.when(() -> Validator.isValidPrimDescription(anyString())).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.isBenchmarkDeleted(anyInt())).thenReturn(false);
            processorsMock.when(() -> Processors.get(anyInt())).thenReturn(proc);
            processorSecurityMock.when(() -> ProcessorSecurity.canUserSeeProcessor(anyInt(), anyInt())).thenReturn(new ValidatorStatusCode(false));
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId)).thenCallRealMethod();

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId);

            // then
            assertFalse(status.isSuccess(), "Should not be able to edit benchmark if processor is not bench type.");
        }
    }

    @Test
    public void userCanEditBenchmarkIfUserCanSeeProcessor() {
        try (MockedStatic<Validator> validatorMock = Mockito.mockStatic(Validator.class);
             MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class);
             MockedStatic<BenchmarkSecurity> benchmarkSecurityMock = Mockito.mockStatic(BenchmarkSecurity.class);
             MockedStatic<Processors> processorsMock = Mockito.mockStatic(Processors.class);
             MockedStatic<ProcessorSecurity> processorSecurityMock = Mockito.mockStatic(ProcessorSecurity.class)) {
            // given
            final int userId = 3;
            Benchmark bench = mock(Benchmark.class);
            Processor proc = mock(Processor.class);
            given(proc.getType()).willReturn(ProcessorType.BENCH);
            validatorMock.when(() -> Validator.isValidBenchName(anyString())).thenReturn(true);
            validatorMock.when(() -> Validator.isValidPrimDescription(anyString())).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(bench);
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId)).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.isBenchmarkDeleted(anyInt())).thenReturn(false);
            processorsMock.when(() -> Processors.get(anyInt())).thenReturn(proc);
            processorSecurityMock.when(() -> ProcessorSecurity.canUserSeeProcessor(anyInt(), anyInt())).thenReturn(new ValidatorStatusCode(true));
            benchmarkSecurityMock.when(() -> BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId)).thenCallRealMethod();

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserEditBenchmark(1, "name", "description", 2, userId);

            // then
            assertTrue(status.isSuccess(), "Should not be able to edit benchmark if processor is not bench type.");
        }
    }

    @Test
    public void userOwnsBenchOrIsAdminFalseIfBenchIsNull() {
        // given
        Benchmark bench = null;

        // when
        boolean success = BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, 1);

        // then
        assertFalse(success, "Bench is null so nobody should own it.");
    }

    @Test
    public void userOwnsBenchOrIsAdminFalseIfUserDoesNotOwnBenchAndIsNotAdmin() {
        try (MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int userId = 1;
            int otherUserId = 2;
            Benchmark bench = mock(Benchmark.class);
            given(bench.getUserId()).willReturn(otherUserId);
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(anyInt())).thenReturn(false);

            // when
            boolean success = BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId);

            // then
            assertFalse(success, "User does not own benchmark and is not admin so should be false.");
        }
    }

    @Test
    public void userOwnsBenchOrIsAdminTrueIfUserOwnsBench() {
        try (MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int userId = 1;
            Benchmark bench = mock(Benchmark.class);
            given(bench.getUserId()).willReturn(userId);
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(anyInt())).thenReturn(false);

            // when
            boolean success = BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId);

            // then
            assertTrue(success, "User owns benchmark so should be true.");
        }
    }

    @Test
    public void userOwnsBenchOrIsAdminTrueIfUserIsAdmin() {
        try (MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int userId = 1;
            int otherUserId = 2;
            Benchmark bench = mock(Benchmark.class);
            given(bench.getUserId()).willReturn(otherUserId);
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(anyInt())).thenReturn(true);

            // when
            boolean success = BenchmarkSecurity.userOwnsBenchOrIsAdmin(bench, userId);

            // then
            assertTrue(success, "User has admin privileges so should be true.");
        }
    }

    @Test
    public void userCantRecycleOrphanedBenchmarksIfUserCantBeFound() {
        try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class)) {
            // given
            usersMock.when(() -> Users.get(anyInt())).thenReturn(null);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserRecycleOrphanedBenchmarks(1, 2);

            // then
            assertFalse(status.isSuccess(), "User can't recycle orphaned benchmarks if owner cant be found.");
        }
    }

    @Test
    public void userCantRecycleOrphanedBenchmarksIfUserIsNotAdminOrOwner() {
        try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int owner = 1;
            int userId = 2;
            usersMock.when(() -> Users.get(anyInt())).thenReturn(mock(User.class));
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(userId)).thenReturn(false);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserRecycleOrphanedBenchmarks(owner, userId);

            // then
            assertFalse(status.isSuccess(), "User is neither owner or admin so cannot recycle orphaned benchmarks.");
        }
    }

    @Test
    public void userCanRecycleOrphanedBenchmarksIfAdmin() {
        try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int owner = 1;
            int userId = 2;
            usersMock.when(() -> Users.get(anyInt())).thenReturn(mock(User.class));

            // user is admin
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(userId)).thenReturn(true);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserRecycleOrphanedBenchmarks(owner, userId);

            // then
            assertTrue(status.isSuccess(), "User is admin so should be able to recycle orphaned benchmarks.");
        }
    }

    @Test
    public void userCanRecycleOrphanedBenchmarksIfOwner() {
        try (MockedStatic<Users> usersMock = Mockito.mockStatic(Users.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given

            // user is owner
            int owner = 1;
            usersMock.when(() -> Users.get(anyInt())).thenReturn(mock(User.class));
            // user is not admin
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminWritePrivileges(owner)).thenReturn(false);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canUserRecycleOrphanedBenchmarks(owner, owner);

            // then
            assertTrue(status.isSuccess(), "User is owner so should be able to recycle orphaned benchmarks.");
        }
    }

    @Test
    public void userCantGetBenchmarkJsonIfUserCantSeeBenchmark() {
        try (MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class)) {
            // given
            permissionsMock.when(() -> Permissions.canUserSeeBench(anyInt(), anyInt())).thenReturn(false);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canGetJsonBenchmark(1, 2);

            // then
            assertFalse(status.isSuccess(), "User cannot see benchmark so they cannot get the JSON.");
        }
    }

    @Test
    public void userCantGetBenchmarkJsonIfBenchmarkCantBeFound() {
        try (MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class)) {
            // given
            permissionsMock.when(() -> Permissions.canUserSeeBench(anyInt(), anyInt())).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(null);

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canGetJsonBenchmark(1, 2);

            // then
            assertFalse(status.isSuccess(), "Benchmark cant be found so user cannot get the JSON.");
        }
    }

    @Test
    public void userCanGetBenchmarkJsonIfUserCanSeeBenchmarkAndItCanBeFound() {
        try (MockedStatic<Permissions> permissionsMock = Mockito.mockStatic(Permissions.class);
             MockedStatic<Benchmarks> benchmarksMock = Mockito.mockStatic(Benchmarks.class)) {
            // given
            permissionsMock.when(() -> Permissions.canUserSeeBench(anyInt(), anyInt())).thenReturn(true);
            benchmarksMock.when(() -> Benchmarks.getIncludeDeletedAndRecycled(anyInt(), anyBoolean())).thenReturn(mock(Benchmark.class));

            // when
            ValidatorStatusCode status = BenchmarkSecurity.canGetJsonBenchmark(1, 2);

            // then
            assertTrue(status.isSuccess(), "User should be able to get Benchmark JSON since they can see it.");
        }
    }

    @Test
    public void userCantSeeBenchmarkUploadStatusIfItCantBeFound() {
        try (MockedStatic<Uploads> uploadsMock = Mockito.mockStatic(Uploads.class)) {
            // given
            uploadsMock.when(() -> Uploads.getBenchmarkStatus(anyInt())).thenReturn(null);

            // when
            boolean success = BenchmarkSecurity.canUserSeeBenchmarkStatus(1, 2);

            // then
            assertFalse(success, "User should not be able to see benchmark upload status if it cannot be found.");
        }
    }

    @Test
    public void userCanSeeBenchmarkUploadStatusIfUserHasAdminPrivileges() {
        try (MockedStatic<Uploads> uploadsMock = Mockito.mockStatic(Uploads.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            uploadsMock.when(() -> Uploads.getBenchmarkStatus(anyInt())).thenReturn(mock(BenchmarkUploadStatus.class));
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(anyInt())).thenReturn(true);

            // when
            boolean success = BenchmarkSecurity.canUserSeeBenchmarkStatus(1, 2);

            // then
            assertTrue(success, "User is admin so should be able to see bench upload status.");
        }
    }

    @Test
    public void userCantSeeBenchmarkUploadStatusIfUserIsNotOwner() {
        try (MockedStatic<Uploads> uploadsMock = Mockito.mockStatic(Uploads.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int userId = 2;
            int owner = 3;
            BenchmarkUploadStatus uploadStatus = mock(BenchmarkUploadStatus.class);
            given(uploadStatus.getUserId()).willReturn(owner);
            uploadsMock.when(() -> Uploads.getBenchmarkStatus(anyInt())).thenReturn(uploadStatus);
            // User is not admin.
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(anyInt())).thenReturn(false);

            // when
            boolean success = BenchmarkSecurity.canUserSeeBenchmarkStatus(1, userId);

            // then
            assertFalse(success, "User is not owner so should not be able to see upload status.");
        }
    }

    @Test
    public void userCanSeeBenchmarkUploadStatusIfUserIsOwner() {
        try (MockedStatic<Uploads> uploadsMock = Mockito.mockStatic(Uploads.class);
             MockedStatic<GeneralSecurity> generalSecurityMock = Mockito.mockStatic(GeneralSecurity.class)) {
            // given
            int userId = 2;
            BenchmarkUploadStatus uploadStatus = mock(BenchmarkUploadStatus.class);
            given(uploadStatus.getUserId()).willReturn(userId);
            uploadsMock.when(() -> Uploads.getBenchmarkStatus(anyInt())).thenReturn(uploadStatus);
            // User is not admin.
            generalSecurityMock.when(() -> GeneralSecurity.hasAdminReadPrivileges(anyInt())).thenReturn(false);

            // when
            boolean success = BenchmarkSecurity.canUserSeeBenchmarkStatus(1, userId);

            // then
            assertTrue(success, "User is owner so should be able to see upload status.");
        }
    }
}
