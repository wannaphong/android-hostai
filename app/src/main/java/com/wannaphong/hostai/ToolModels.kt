package com.wannaphong.hostai

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam

/**
 * Data class representing a tool/function definition in OpenAI format.
 */
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

/**
 * Data class representing a function definition.
 */
data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: FunctionParameters?
)

/**
 * Data class representing function parameters schema.
 */
data class FunctionParameters(
    val type: String = "object",
    val properties: Map<String, PropertyDefinition>,
    val required: List<String>? = null
)

/**
 * Data class representing a parameter property.
 */
data class PropertyDefinition(
    val type: String,
    val description: String? = null,
    val enum: List<String>? = null
)

/**
 * Data class representing a tool call in the model response.
 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

/**
 * Data class representing a function call.
 */
data class FunctionCall(
    val name: String,
    val arguments: String
)

/**
 * Example tool set for demonstration purposes.
 * This provides simple utility functions that can be called by the model.
 */
class ExampleToolSet {
    
    @Tool(description = "Get the current weather for a city")
    fun getCurrentWeather(
        @ToolParam(description = "The city name, e.g., San Francisco") city: String,
        @ToolParam(description = "Optional country code, e.g., US") country: String? = null,
        @ToolParam(description = "Temperature unit (celsius or fahrenheit). Default: celsius") unit: String = "celsius"
    ): Map<String, Any> {
        // Mock implementation - in a real application, you would call a weather API
        return mapOf(
            "city" to city,
            "country" to (country ?: "Unknown"),
            "temperature" to 22,
            "unit" to unit,
            "condition" to "Sunny",
            "humidity" to 65
        )
    }
    
    @Tool(description = "Calculate the sum of a list of numbers")
    fun sum(
        @ToolParam(description = "The numbers to sum, can be floating point") numbers: List<Double>
    ): Double {
        return numbers.sum()
    }
    
    @Tool(description = "Get the current time in a specific timezone")
    fun getCurrentTime(
        @ToolParam(description = "The timezone, e.g., America/New_York, Europe/London, or UTC") timezone: String = "UTC"
    ): Map<String, Any> {
        // Mock implementation
        return mapOf(
            "timezone" to timezone,
            "time" to "14:30:00",
            "date" to "2024-01-15",
            "timestamp" to System.currentTimeMillis() / 1000
        )
    }
}
