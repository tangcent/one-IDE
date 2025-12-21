import * as assert from 'assert';
import { AITool, AIConfig } from '../../services/AITool';
import { ConfigService } from '../../services/ConfigService';

// Mock ConfigService
class MockConfigService extends ConfigService {
    private mockConfig: any;

    constructor(mockConfig: any = { currentTool: 'Auto' }) {
        super(undefined); // Pass undefined to use default path or a dummy path
        this.mockConfig = mockConfig;
    }

    public getConfig() {
        return this.mockConfig;
    }
}

describe('AITool Service', () => {
    let aiTool: AITool;
    let mockConfigService: MockConfigService;

    beforeEach(() => {
        mockConfigService = new MockConfigService();
        
        const mockContext = {
            extensionPath: process.cwd(),
            subscriptions: []
        } as any;

        // Initialize AITool
        AITool.initialize(mockContext, mockConfigService);
        aiTool = AITool.getInstance();
    });

    afterEach(() => {
        if (mockConfigService) {
            mockConfigService.dispose();
        }
    });

    it('should load AI configs from json', () => {
        const configs = aiTool.getAllAIConfigs();
        assert.ok(configs, 'Configs should be loaded');
        assert.ok(Object.keys(configs).length > 0, 'Should have some tools configured');
        
        // Check for specific tools
        assert.ok(configs['Cursor'], 'Cursor should be configured');
        assert.ok(configs['Trae'], 'Trae should be configured');
        assert.ok(configs['Windsurf'], 'Windsurf should be configured');
    });

    it('should resolve tool based on user config', () => {
        // Using the pure resolveTool method
        const resolved = aiTool.resolveTool('Cursor', 'Code', () => false);
        assert.strictEqual(resolved, 'Cursor');
    });

    it('should resolve tool based on app name (IDE Fork)', () => {
        // Case insensitive match
        let resolved = aiTool.resolveTool('Auto', 'Trae', () => false);
        assert.strictEqual(resolved, 'Trae');

        resolved = aiTool.resolveTool('Auto', 'Windsurf', () => false);
        assert.strictEqual(resolved, 'Windsurf');
    });

    it('should resolve tool based on installed plugins', () => {
        // Mock plugin check
        const isPluginInstalled = (id: string) => {
            return id === 'MarsCode.marscode-extension'; // Trae plugin ID
        };

        const resolved = aiTool.resolveTool('Auto', 'Code', isPluginInstalled);
        assert.strictEqual(resolved, 'Trae');
    });

    it('should prioritize user config over everything else', () => {
        const isPluginInstalled = (id: string) => true; // All plugins installed
        const resolved = aiTool.resolveTool('Cursor', 'Trae', isPluginInstalled);
        
        // Should be Cursor because it's explicitly configured
        assert.strictEqual(resolved, 'Cursor');
    });

    it('should return undefined if no tool detected', () => {
        const resolved = aiTool.resolveTool('Auto', 'UnknownIDE', () => false);
        assert.strictEqual(resolved, undefined);
    });
});
