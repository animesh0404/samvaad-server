package com.samvaad.samvaad_server.config;

import com.samvaad.samvaad_server.user.AdminBootstrapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapRunnerTest {

    @Mock
    private AdminBootstrapService adminBootstrapService;

    private AdminBootstrapRunner adminBootstrapRunner;

    @BeforeEach
    void setUp() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties("", "", null);
        adminBootstrapRunner = new AdminBootstrapRunner(adminBootstrapService, properties);
    }

    @Test
    void skipsBootstrapWithoutFailingWhenCredentialsAreMissing() {
        assertDoesNotThrow(() -> adminBootstrapRunner.run(null));

        then(adminBootstrapService).shouldHaveNoInteractions();
    }
}