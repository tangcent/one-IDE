import * as assert from 'assert';
import { FolderRuleBuilder, SingleFileRuleBuilder } from '../../services/RuleService';

describe('RuleBuilders', () => {
    
    describe('FolderRuleBuilder', () => {
        it('should maintain file structure and ensure markdown extension', () => {
            const builder = new FolderRuleBuilder('.trae/rules');
            const sourceFiles = [
                { path: 'some/path/rule1.md', content: 'content1' },
                { path: 'rule2', content: 'content2' } // Missing extension
            ];

            const rules = builder.buildRules(sourceFiles, 'Cursor');

            assert.strictEqual(rules.length, 2);
            
            assert.strictEqual(rules[0].path, '.trae/rules/rule1.md'); // path.join uses os separator, assume / for now or normalized
            assert.strictEqual(rules[0].content, 'content1');
            
            assert.strictEqual(rules[1].path, '.trae/rules/rule2.md');
            assert.strictEqual(rules[1].content, 'content2');
        });
    });

    describe('SingleFileRuleBuilder', () => {
        it('should concatenate files into one', () => {
            const builder = new SingleFileRuleBuilder('.codiumai.toml');
            const sourceFiles = [
                { path: 'rule1.md', content: 'content1' },
                { path: 'rule2.md', content: 'content2' }
            ];

            const rules = builder.buildRules(sourceFiles, 'Cursor');

            assert.strictEqual(rules.length, 1);
            const rule = rules[0];
            assert.strictEqual(rule.path, '.codiumai.toml');
            
            // Check content format
            const expectedContent = "content1\n\ncontent2\n\n";
            assert.strictEqual(rule.content, expectedContent);
        });
    });
});
